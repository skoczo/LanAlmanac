#!/bin/bash

# Wait for OpenWRT DHCP to be ready
sleep 20

# Request initial DHCP lease from OpenWRT with Android Vendor Class Identifier (Option 60)
ip addr flush dev eth0 || true
udhcpc -i eth0 -x hostname:android-phone -V android-dhcp-15 -n -q || true

echo "Android phone simulator started. MAC will randomize every 5 minutes."

while true; do
    sleep 300
    
    echo "Randomizing MAC address..."
    
    # Generate a locally administered MAC address (U/L bit set to 1)
    RANDOM_MAC=$(printf '02:%02X:%02X:%02X:%02X:%02X\n' $((RANDOM%256)) $((RANDOM%256)) $((RANDOM%256)) $((RANDOM%256)) $((RANDOM%256)))
    
    echo "New MAC will be: $RANDOM_MAC"
    
    # Bring down interface, change MAC, bring back up
    ip link set dev eth0 down
    ip link set dev eth0 address $RANDOM_MAC
    ip link set dev eth0 up
    
    # Request a new DHCP lease from OpenWRT with the new MAC
    udhcpc -i eth0 -x hostname:android-phone -V android-dhcp-15 -n -q || true
    
    echo "Done. Waiting 5 minutes before next randomization..."
done
