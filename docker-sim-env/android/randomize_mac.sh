#!/bin/bash

# Flush IP and randomize MAC immediately on startup to prevent GNM from discovering the default container state
ip addr flush dev eth0 || true
RANDOM_MAC=$(printf '02:%02X:%02X:%02X:%02X:%02X\n' $((RANDOM%256)) $((RANDOM%256)) $((RANDOM%256)) $((RANDOM%256)) $((RANDOM%256)))
ip link set dev eth0 down || true
ip link set dev eth0 address $RANDOM_MAC || true
ip link set dev eth0 up || true

# Android-like DHCP Option 55 parameter request list (consistent across roaming events)
# This is a real Android 14+ fingerprint: Subnet Mask, Router, DNS, Domain, Broadcast, NTP, Lease Time, Renewal, Rebind
ANDROID_OPT55="1,3,6,15,28,42,51,58,59"
ANDROID_OPT60="android-dhcp-15"

echo "Requesting initial DHCP lease from OpenWRT..."
until udhcpc -i eth0 -x hostname:android-phone -V "$ANDROID_OPT60" -n -q; do
    echo "Waiting for OpenWRT DHCP server..."
    sleep 1
done

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
    udhcpc -i eth0 -x hostname:android-phone -V "$ANDROID_OPT60" -n -q || true
    
    echo "Done. Waiting 5 minutes before next randomization..."
done
