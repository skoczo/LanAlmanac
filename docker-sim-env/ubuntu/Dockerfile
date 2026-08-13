FROM ubuntu:latest

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server iproute2 iputils-ping curl isc-dhcp-client && \
    mkdir -p /var/run/sshd && \
    echo 'root:root' | chpasswd && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config && \
    sed -i 's/UsePAM yes/UsePAM no/' /etc/ssh/sshd_config && \
    echo 'send host-name "ubuntu-ne";' > /etc/dhcp/dhclient.conf

EXPOSE 22
# Wait for OpenWRT DHCP to be ready, then request a lease (same IP as Docker static)
CMD ["/bin/bash", "-c", "sleep 15 && dhclient eth0 ; /usr/sbin/sshd -D"]
