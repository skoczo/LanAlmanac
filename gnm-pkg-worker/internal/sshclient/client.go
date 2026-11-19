package sshclient

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"
)

type AuthType string

const (
	AuthTypePassword AuthType = "PASSWORD"
	AuthTypeSSHKey   AuthType = "SSH_KEY"

	DefaultMaxOutputBytes = 16 << 20 // 16 MiB
)

var envVarKeyPattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

// AuthError indicates SSH authentication was rejected by the remote host.
type AuthError struct {
	Message string
}

func (e *AuthError) Error() string { return e.Message }

// ConnectionRefusedError indicates TCP connection was refused.
type ConnectionRefusedError struct {
	Message string
}

func (e *ConnectionRefusedError) Error() string { return e.Message }

// ConnectionTimeoutError indicates TCP/SSH handshake timed out.
type ConnectionTimeoutError struct {
	Message string
}

func (e *ConnectionTimeoutError) Error() string { return e.Message }

// CommandTimeoutError indicates remote command execution exceeded timeout.
type CommandTimeoutError struct {
	Message string
}

func (e *CommandTimeoutError) Error() string { return e.Message }

// OutputLimitError indicates remote command output exceeded maximum buffer size.
type OutputLimitError struct {
	Message string
}

func (e *OutputLimitError) Error() string { return e.Message }

// ConnectionLostError indicates the SSH channel closed without an exit status.
type ConnectionLostError struct {
	Message string
}

func (e *ConnectionLostError) Error() string { return e.Message }

// IdleTimeoutError indicates the remote command produced no output within the idle timeout window.
type IdleTimeoutError struct {
	Message string
}

func (e *IdleTimeoutError) Error() string { return e.Message }

// Target holds parameters for establishing a TOFU-verified SSH connection.
type Target struct {
	Host                  string
	Port                  int
	Username              string
	ExpectedHostKeySHA256 string
	AuthType              AuthType
	Secret                []byte
	ConnectTimeout        time.Duration
}

// Conn wraps an authenticated SSH client connection.
type Conn struct {
	client  *ssh.Client
	netConn net.Conn
}

// Close closes the underlying SSH and TCP connections.
func (c *Conn) Close() error {
	if c == nil || c.client == nil {
		return nil
	}
	return c.client.Close()
}

// ExecResult contains buffered stdout, stderr, and exit code of a remote command.
type ExecResult struct {
	Stdout   string
	Stderr   string
	ExitCode int
}

// Dial connects to the target SSH server, enforces TOFU host key verification BEFORE sending
// any authentication material, and guarantees that Target.Secret is zeroed before returning.
func Dial(ctx context.Context, target Target) (*Conn, error) {
	defer clear(target.Secret)

	if strings.TrimSpace(target.Host) == "" {
		return nil, &InvalidTargetError{Message: "host is required"}
	}
	if target.Port < 1 || target.Port > 65535 {
		return nil, &InvalidTargetError{Message: "port must be between 1 and 65535"}
	}
	if strings.TrimSpace(target.Username) == "" {
		return nil, &InvalidTargetError{Message: "username is required"}
	}
	if err := ValidateExpectedFingerprint(target.ExpectedHostKeySHA256); err != nil {
		return nil, err
	}
	if len(target.Secret) == 0 {
		return nil, &InvalidTargetError{Message: "auth secret is required"}
	}

	authMethod, err := buildAuthMethod(target.AuthType, target.Secret)
	if err != nil {
		return nil, err
	}

	timeout := target.ConnectTimeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}

	dialCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	addr := net.JoinHostPort(target.Host, strconv.Itoa(target.Port))
	var d net.Dialer
	netConn, err := d.DialContext(dialCtx, "tcp", addr)
	if err != nil {
		return nil, classifyDialError(err)
	}

	if deadline, ok := dialCtx.Deadline(); ok {
		_ = netConn.SetDeadline(deadline)
	}

	cfg := &ssh.ClientConfig{
		User:            target.Username,
		Auth:            []ssh.AuthMethod{authMethod},
		HostKeyCallback: verifyHostKeyCallback(target.ExpectedHostKeySHA256),
		Timeout:         timeout,
	}

	sshConn, chans, reqs, err := ssh.NewClientConn(netConn, addr, cfg)
	if err != nil {
		_ = netConn.Close()
		var mismatchErr *HostKeyMismatchError
		if errors.As(err, &mismatchErr) {
			return nil, mismatchErr
		}
		if strings.Contains(strings.ToLower(err.Error()), "unable to authenticate") {
			return nil, &AuthError{Message: "SSH authentication failed"}
		}
		return nil, classifyDialError(err)
	}

	// Clear handshake deadline once authenticated so subsequent commands use their own contexts.
	_ = netConn.SetDeadline(time.Time{})

	return &Conn{
		client:  ssh.NewClient(sshConn, chans, reqs),
		netConn: netConn,
	}, nil
}

func buildAuthMethod(authType AuthType, secret []byte) (ssh.AuthMethod, error) {
	switch authType {
	case AuthTypePassword:
		secretCopy := make([]byte, len(secret))
		copy(secretCopy, secret)
		return ssh.PasswordCallback(func() (string, error) {
			defer clear(secretCopy)
			return string(secretCopy), nil
		}), nil
	case AuthTypeSSHKey:
		signer, err := ssh.ParsePrivateKey(secret)
		if err != nil {
			return nil, &AuthError{Message: "Invalid or unsupported SSH private key"}
		}
		return ssh.PublicKeys(signer), nil
	default:
		return nil, &InvalidTargetError{Message: fmt.Sprintf("unsupported auth type %q", authType)}
	}
}

// ExecBuffered executes a command over SSH, reading stdout and stderr concurrently
// to prevent SSH window deadlocks, and enforces a strict byte cap on output.
func (c *Conn) ExecBuffered(ctx context.Context, command string, env map[string]string, maxBytes int64) (ExecResult, error) {
	if maxBytes <= 0 {
		maxBytes = DefaultMaxOutputBytes
	}

	fullCmd, err := PrependPOSIXEnv(command, env)
	if err != nil {
		return ExecResult{}, err
	}

	sess, err := c.client.NewSession()
	if err != nil {
		return ExecResult{}, fmt.Errorf("create SSH session: %w", err)
	}
	defer sess.Close()

	stdoutPipe, err := sess.StdoutPipe()
	if err != nil {
		return ExecResult{}, fmt.Errorf("open stdout pipe: %w", err)
	}
	stderrPipe, err := sess.StderrPipe()
	if err != nil {
		return ExecResult{}, fmt.Errorf("open stderr pipe: %w", err)
	}

	if err := sess.Start(fullCmd); err != nil {
		return ExecResult{}, fmt.Errorf("start remote command: %w", err)
	}

	var (
		stdoutBuf, stderrBuf bytes.Buffer
		stdoutErr, stderrErr error
		wg                   sync.WaitGroup
	)
	wg.Add(2)
	go func() {
		defer wg.Done()
		stdoutErr = readBoundedStream(stdoutPipe, &stdoutBuf, maxBytes)
	}()
	go func() {
		defer wg.Done()
		stderrErr = readBoundedStream(stderrPipe, &stderrBuf, maxBytes)
	}()

	waitCh := make(chan error, 1)
	go func() {
		wg.Wait()
		waitCh <- sess.Wait()
	}()

	var waitErr error
	select {
	case <-ctx.Done():
		_ = sess.Close()
		return ExecResult{}, &CommandTimeoutError{Message: "Remote command timed out"}
	case waitErr = <-waitCh:
	}

	if stdoutErr != nil {
		return ExecResult{}, stdoutErr
	}
	if stderrErr != nil {
		return ExecResult{}, stderrErr
	}

	exitCode := 0
	if waitErr != nil {
		var exitErr *ssh.ExitError
		if errors.As(waitErr, &exitErr) {
			exitCode = exitErr.ExitStatus()
		} else {
			return ExecResult{}, fmt.Errorf("remote command failed: %w", waitErr)
		}
	}

	return ExecResult{
		Stdout:   stdoutBuf.String(),
		Stderr:   stderrBuf.String(),
		ExitCode: exitCode,
	}, nil
}

// ExecStreaming executes a command over SSH, streaming stdout and stderr lines concurrently
// in real time and enforcing both total duration and idle timeouts.
func (c *Conn) ExecStreaming(
	ctx context.Context,
	command string,
	env map[string]string,
	idleTimeout time.Duration,
	onLine func(stream, line string),
) (int, error) {
	fullCmd, err := PrependPOSIXEnv(command, env)
	if err != nil {
		return -1, err
	}

	sess, err := c.client.NewSession()
	if err != nil {
		return -1, &ConnectionLostError{Message: "SSH session creation failed: " + err.Error()}
	}
	defer sess.Close()

	stdoutPipe, err := sess.StdoutPipe()
	if err != nil {
		return -1, err
	}
	stderrPipe, err := sess.StderrPipe()
	if err != nil {
		return -1, err
	}

	if err := sess.Start(fullCmd); err != nil {
		return -1, err
	}

	var (
		activityMu   sync.Mutex
		onLineMu     sync.Mutex
		lastActivity = time.Now()
		wg           sync.WaitGroup
	)
	touch := func() {
		activityMu.Lock()
		lastActivity = time.Now()
		activityMu.Unlock()
	}

	streamReader := func(tag string, r io.Reader) {
		defer wg.Done()
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			touch()
			if onLine != nil {
				line := sc.Text()
				onLineMu.Lock()
				onLine(tag, line)
				onLineMu.Unlock()
			}
		}
	}

	wg.Add(2)
	go streamReader("STDOUT", stdoutPipe)
	go streamReader("STDERR", stderrPipe)

	waitCh := make(chan error, 1)
	go func() {
		wg.Wait()
		waitCh <- sess.Wait()
	}()

	var idleTicker *time.Ticker
	var idleCh <-chan time.Time
	if idleTimeout > 0 {
		idleTicker = time.NewTicker(time.Second)
		defer idleTicker.Stop()
		idleCh = idleTicker.C
	}

	for {
		select {
		case <-ctx.Done():
			_ = sess.Close()
			if errors.Is(ctx.Err(), context.Canceled) {
				return -1, context.Canceled
			}
			return -1, &CommandTimeoutError{Message: "Remote command timed out"}
		case <-idleCh:
			activityMu.Lock()
			elapsed := time.Since(lastActivity)
			activityMu.Unlock()
			if elapsed > idleTimeout {
				_ = sess.Close()
				return -1, &IdleTimeoutError{Message: "Remote command exceeded idle timeout"}
			}
		case waitErr := <-waitCh:
			if waitErr == nil {
				return 0, nil
			}
			var exitMissing *ssh.ExitMissingError
			if errors.As(waitErr, &exitMissing) {
				return -1, &ConnectionLostError{Message: "SSH connection closed without exit status"}
			}
			var exitErr *ssh.ExitError
			if errors.As(waitErr, &exitErr) {
				return exitErr.ExitStatus(), nil
			}
			return -1, &ConnectionLostError{Message: waitErr.Error()}
		}
	}
}

func readBoundedStream(r io.Reader, dst *bytes.Buffer, maxBytes int64) error {
	limited := io.LimitReader(r, maxBytes+1)
	n, err := dst.ReadFrom(limited)
	if err != nil {
		return err
	}
	if n > maxBytes {
		return &OutputLimitError{Message: fmt.Sprintf("Command output exceeded %d bytes", maxBytes)}
	}
	return nil
}

// PrependPOSIXEnv safely prepends validated environment variable exports to a POSIX command.
func PrependPOSIXEnv(command string, env map[string]string) (string, error) {
	if len(env) == 0 {
		return command, nil
	}
	keys := make([]string, 0, len(env))
	for k := range env {
		if !envVarKeyPattern.MatchString(k) {
			return "", &InvalidTargetError{Message: fmt.Sprintf("invalid environment variable name %q", k)}
		}
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var b strings.Builder
	b.WriteString("export")
	for _, k := range keys {
		b.WriteByte(' ')
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(QuotePOSIX(env[k]))
	}
	b.WriteString("; ")
	b.WriteString(command)
	return b.String(), nil
}

// QuotePOSIX wraps a string in POSIX single quotes, escaping embedded single quotes.
func QuotePOSIX(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func classifyDialError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return &ConnectionTimeoutError{Message: "SSH connection timed out"}
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return &ConnectionTimeoutError{Message: "SSH connection timed out"}
	}
	if errors.Is(err, syscall.ECONNREFUSED) || strings.Contains(strings.ToLower(err.Error()), "connection refused") {
		return &ConnectionRefusedError{Message: "SSH connection refused"}
	}
	return err
}

