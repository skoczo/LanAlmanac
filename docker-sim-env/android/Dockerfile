FROM alpine:latest

RUN apk add --no-cache iproute2 bash coreutils netcat-openbsd python3

COPY randomize_mac.sh /usr/local/bin/randomize_mac.sh
COPY ssdp_responder.py /usr/local/bin/ssdp_responder.py
RUN chmod +x /usr/local/bin/randomize_mac.sh

# Expose typical Android ports (8008 = Google Cast, 5555 = ADB, 1900 = SSDP)
EXPOSE 8008 5555 1900
CMD ["/bin/bash", "-c", "nc -lk -p 8008 & python3 /usr/local/bin/ssdp_responder.py & /usr/local/bin/randomize_mac.sh"]
