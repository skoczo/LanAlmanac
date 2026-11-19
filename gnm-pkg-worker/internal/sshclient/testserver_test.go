package sshclient

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"golang.org/x/crypto/ssh"
)

// mockSSHServer is an in-process SSH server for deterministic TDD tests.
type mockSSHServer struct {
	listener           net.Listener
	hostSigner         ssh.Signer
	hostFingerprint    string
	hostKeyAlgorithm   string
	allowedUser        string
	allowedPassword    string
	allowedPubKeys     []ssh.PublicKey
	execHandler        func(cmd string) (stdout string, stderr string, exitCode uint32)
	passwordAuthCalls  atomic.Int32
	publicKeyAuthCalls atomic.Int32

	mu     sync.Mutex
	closed bool
}

func startMockSSHServer(t *testing.T, opts func(*mockSSHServer)) *mockSSHServer {
	t.Helper()

	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate host ed25519 key: %v", err)
	}
	hostSigner, err := ssh.NewSignerFromKey(hostPriv)
	if err != nil {
		t.Fatalf("create host signer: %v", err)
	}

	s := &mockSSHServer{
		hostSigner:       hostSigner,
		hostFingerprint:  ssh.FingerprintSHA256(hostSigner.PublicKey()),
		hostKeyAlgorithm: hostSigner.PublicKey().Type(),
		allowedUser:      "lanalmanac",
		allowedPassword:  "correct-horse-battery-staple",
		execHandler: func(cmd string) (string, string, uint32) {
			return "ok\n", "", 0
		},
	}
	if opts != nil {
		opts(s)
	}

	cfg := &ssh.ServerConfig{
		PasswordCallback: func(c ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
			s.passwordAuthCalls.Add(1)
			if c.User() == s.allowedUser && string(pass) == s.allowedPassword {
				return &ssh.Permissions{}, nil
			}
			return nil, fmt.Errorf("password rejected for %q", c.User())
		},
		PublicKeyCallback: func(c ssh.ConnMetadata, pub ssh.PublicKey) (*ssh.Permissions, error) {
			s.publicKeyAuthCalls.Add(1)
			if c.User() != s.allowedUser {
				return nil, fmt.Errorf("unknown user %q", c.User())
			}
			pubBytes := pub.Marshal()
			for _, allowed := range s.allowedPubKeys {
				if string(allowed.Marshal()) == string(pubBytes) {
					return &ssh.Permissions{}, nil
				}
			}
			return nil, fmt.Errorf("public key rejected")
		},
	}
	cfg.AddHostKey(hostSigner)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen mock ssh server: %v", err)
	}
	s.listener = ln

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handleConn(conn, cfg)
		}
	}()

	t.Cleanup(func() {
		s.Close()
	})
	return s
}

func (s *mockSSHServer) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed {
		s.closed = true
		_ = s.listener.Close()
	}
}

func (s *mockSSHServer) HostPort() (string, int) {
	host, portStr, _ := net.SplitHostPort(s.listener.Addr().String())
	port, _ := strconv.Atoi(portStr)
	return host, port
}

func (s *mockSSHServer) handleConn(netConn net.Conn, cfg *ssh.ServerConfig) {
	defer netConn.Close()
	sshConn, chans, reqs, err := ssh.NewServerConn(netConn, cfg)
	if err != nil {
		return
	}
	defer sshConn.Close()
	go ssh.DiscardRequests(reqs)

	for newChan := range chans {
		if newChan.ChannelType() != "session" {
			_ = newChan.Reject(ssh.UnknownChannelType, "unknown channel type")
			continue
		}
		ch, requests, err := newChan.Accept()
		if err != nil {
			continue
		}
		go s.handleSession(ch, requests)
	}
}

func (s *mockSSHServer) handleSession(ch ssh.Channel, in <-chan *ssh.Request) {
	defer ch.Close()
	for req := range in {
		if req.Type != "exec" {
			if req.WantReply {
				_ = req.Reply(false, nil)
			}
			continue
		}
		if len(req.Payload) < 4 {
			_ = req.Reply(false, nil)
			continue
		}
		cmdLen := binary.BigEndian.Uint32(req.Payload[:4])
		if int(cmdLen)+4 > len(req.Payload) {
			_ = req.Reply(false, nil)
			continue
		}
		cmd := string(req.Payload[4 : 4+cmdLen])
		_ = req.Reply(true, nil)

		stdout, stderr, exitCode := s.execHandler(cmd)
		if stdout != "" {
			_, _ = ch.Write([]byte(stdout))
		}
		if stderr != "" {
			_, _ = ch.Stderr().Write([]byte(stderr))
		}

		exitPayload := make([]byte, 4)
		binary.BigEndian.PutUint32(exitPayload, exitCode)
		_, _ = ch.SendRequest("exit-status", false, exitPayload)
		return
	}
}

func generateTestEd25519Key(t *testing.T) (pemBytes []byte, pub ssh.PublicKey) {
	t.Helper()
	pubRaw, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate ed25519 key: %v", err)
	}
	block, err := ssh.MarshalPrivateKey(priv, "")
	if err != nil {
		t.Fatalf("marshal openssh ed25519 key: %v", err)
	}
	sshPub, err := ssh.NewPublicKey(pubRaw)
	if err != nil {
		t.Fatalf("new ssh pub key: %v", err)
	}
	return pem.EncodeToMemory(block), sshPub
}

func generateTestRSAKey(t *testing.T) (pemBytes []byte, pub ssh.PublicKey) {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate rsa key: %v", err)
	}
	pemBlock := &pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(priv),
	}
	sshPub, err := ssh.NewPublicKey(&priv.PublicKey)
	if err != nil {
		t.Fatalf("new rsa ssh pub key: %v", err)
	}
	return pem.EncodeToMemory(pemBlock), sshPub
}

func hasPrefixIgnoreCase(s, prefix string) bool {
	return strings.HasPrefix(strings.ToLower(s), strings.ToLower(prefix))
}

