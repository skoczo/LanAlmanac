FROM sulinggg/openwrt:x86_64

# Inject configuration files
COPY network /etc/config/network
COPY dhcp /etc/config/dhcp
COPY firewall /etc/config/firewall

# Set root password to root for SSH access
RUN (echo 'root'; echo 'root') | passwd root

EXPOSE 22
EXPOSE 53/udp
EXPOSE 67/udp

CMD ["/sbin/init"]
