import type { BluetoothDevice } from '../interface';

export const UNKNOWN_DEVICE_NAME = 'Connected device';

export const peerDevice = (deviceId: string): BluetoothDevice => ({
  id: deviceId,
  name: UNKNOWN_DEVICE_NAME,
  rssi: 0,
  paired: false,
});
