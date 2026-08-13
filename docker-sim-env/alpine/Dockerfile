FROM alpine:latest

RUN apk add --no-cache openssh iproute2 bash dhcpcd && \
    ssh-keygen -A && \
    echo 'root:root' | chpasswd && \
    sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config

EXPOSE 22
# Wait for OpenWRT DHCP to be ready, then request a lease (same IP as Docker static)
CMD ["/bin/sh", "-c", "sleep 15 && udhcpc -i eth0 -x hostname:alpine-ne -b ; /usr/sbin/sshd -D"]
