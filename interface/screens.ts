import type { BluetoothDevice } from './bluetooth';

export type HomeScreenProps = {
  onNewChat: () => void;
  onOpenChat: (deviceId: string) => void;
};

export type BluetoothSearchScreenProps = {
  onSelectDevice: (device: BluetoothDevice) => void;
  onBack?: () => void;
};

export type ChatScreenProps = {
  device: BluetoothDevice;
  initiator: boolean;
  onBack: () => void;
};
