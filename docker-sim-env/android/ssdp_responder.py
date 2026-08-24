import socket

# Bind to UDP port 1900
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(('0.0.0.0', 1900))

print("Android SSDP Responder running on port 1900")

while True:
    try:
        data, addr = sock.recvfrom(2048)
        if b'M-SEARCH' in data:
            # Respond with a consistent UUID hardware identifier
            response = (
                "HTTP/1.1 200 OK\r\n"
                "Cache-Control: max-age=1800\r\n"
                "ST: upnp:rootdevice\r\n"
                "USN: uuid:android-sim-hardware-uuid-12345::upnp:rootdevice\r\n"
                "Server: Android/14 UPnP/1.0\r\n"
                "\r\n"
            )
            sock.sendto(response.encode('utf-8'), addr)
    except Exception as e:
        pass
