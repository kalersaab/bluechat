import type { BluetoothDevice } from './bluetooth';

export type RootStackParamList = {
  Home: undefined;
  Search: undefined;
  Chat: {
    device: BluetoothDevice;
    initiator: boolean;
  };
};
