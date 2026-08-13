FROM alpine:latest

RUN apk add --no-cache iproute2 bash coreutils netcat-openbsd

COPY randomize_mac.sh /usr/local/bin/randomize_mac.sh
RUN chmod +x /usr/local/bin/randomize_mac.sh

# Expose typical Android ports (8008 = Google Cast, 5555 = ADB)
EXPOSE 8008 5555
CMD ["/bin/bash", "-c", "nc -lk -p 8008 & /usr/local/bin/randomize_mac.sh"]
