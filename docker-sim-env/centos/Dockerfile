FROM quay.io/centos/centos:stream9

RUN dnf -y install openssh-server openssh-clients iproute iputils dhcp-client && \
    dnf clean all && \
    ssh-keygen -A && \
    echo 'root:root' | chpasswd && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config || true && \
    sed -i 's/#PermitRootLogin yes/PermitRootLogin yes/' /etc/ssh/sshd_config || true && \
    echo 'send host-name "centos-ne";' > /etc/dhcp/dhclient.conf

EXPOSE 22
# Wait for OpenWRT DHCP to be ready; redirect stderr to suppress systemd hook errors in containers
CMD ["/bin/bash", "-c", "sleep 15 && dhclient eth0 2>/dev/null ; /usr/sbin/sshd -D"]
