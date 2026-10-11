import { useEffect } from 'react';
import { TurboModuleRegistry } from 'react-native';
import { saveMessage, updateMessageStatus } from '../db/chatDatabase';
import type { Spec } from '../specs/NativeBluetoothScanner';

const NativeModule = TurboModuleRegistry.get<Spec>('BluetoothScanner');

function MessageHandler() {
  useEffect(() => {
    if (!NativeModule) {
      return;
    }

    const messageSubscription = NativeModule.onMessageReceived(message => {
      saveMessage({
        deviceId: message.deviceId,
        messageId: message.messageId,
        text: message.text,
        sender: 'them',
        timestamp: message.timestamp,
        status: 'read',
      }).catch(() => undefined);
    });

    const ackSubscription = NativeModule.onAckReceived(ack => {
      updateMessageStatus(ack.messageId, 'delivered').catch(() => undefined);
    });

    return () => {
      messageSubscription.remove();
      ackSubscription.remove();
    };
  }, []);

  return null;
}

export default MessageHandler;
