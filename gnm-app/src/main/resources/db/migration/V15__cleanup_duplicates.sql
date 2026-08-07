-- Delete duplicate physical devices created by concurrent sightings race condition
-- We keep the one with the smallest UUID and delete the others that share the same IP address.
-- Only delete unmanaged 'DISCOVERED' devices.

DELETE FROM physical_device
WHERE id IN (
    SELECT n1.physical_device_id
    FROM network_identity n1
    JOIN network_identity n2 ON n1.ip_address = n2.ip_address
    WHERE n1.physical_device_id > n2.physical_device_id
)
AND management_state = 'DISCOVERED';
