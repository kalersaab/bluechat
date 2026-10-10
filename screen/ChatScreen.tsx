import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  FlatList,
  KeyboardAvoidingView,
  ListRenderItem,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  TurboModuleRegistry,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  getMessagesForDevice,
  saveMessage,
  type ChatMessage,
  type ChatSender,
} from '../db/chatDatabase';
import type {
  BluetoothDevice,
  Spec,
} from '../specs/NativeBluetoothScanner';

const NativeModule = TurboModuleRegistry.get<Spec>('BluetoothScanner');

type Message = ChatMessage;

const mergeMessages = (primary: Message[], secondary: Message[]): Message[] => {
  const seen = new Set(primary.map(item => item.id));
  const merged = [...primary];
  for (const item of secondary) {
    if (!seen.has(item.id)) {
      seen.add(item.id);
      merged.push(item);
    }
  }
  return merged.sort((left, right) => {
    if (left.timestamp !== right.timestamp) {
      return left.timestamp - right.timestamp;
    }
    return left.id.localeCompare(right.id);
  });
};

type ChatScreenProps = {
  device: BluetoothDevice;
  initiator: boolean;
  onBack: () => void;
};

const formatTime = (timestamp: number): string => {
  const date = new Date(timestamp);
  const hours = date.getHours().toString().padStart(2, '0');
  const minutes = date.getMinutes().toString().padStart(2, '0');
  return `${hours}:${minutes}`;
};

function ChatScreen({ device, initiator, onBack }: ChatScreenProps) {
  const insets = useSafeAreaInsets();
  const listRef = useRef<FlatList<Message>>(null);
  const [draft, setDraft] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [connected, setConnected] = useState(!initiator);

  const persistMessage = useCallback(
    async (text: string, sender: ChatSender) => {
      const timestamp = Date.now();
      try {
        const saved = await saveMessage({
          deviceId: device.id,
          text,
          sender,
          timestamp,
        });
        setMessages(previous => mergeMessages(previous, [saved]));
      } catch {
        setMessages(previous =>
          mergeMessages(previous, [
            {
              id: `local-${sender}-${timestamp}`,
              text,
              sender,
              timestamp,
            },
          ]),
        );
      }
    },
    [device.id],
  );

  useEffect(() => {
    let cancelled = false;
    getMessagesForDevice(device.id)
      .then(history => {
        if (!cancelled) {
          setMessages(previous => mergeMessages(history, previous));
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [device.id]);

  useEffect(() => {
    if (!NativeModule) {
      return;
    }
    const connectionSubscription = NativeModule.onConnectionStateChanged(
      state => setConnected(state.connected),
    );
    const messageSubscription = NativeModule.onMessageReceived(message => {
      persistMessage(message.text, 'them');
    });

    if (initiator) {
      NativeModule.connect(device.id);
    }

    return () => {
      connectionSubscription.remove();
      messageSubscription.remove();
      NativeModule.disconnect();
    };
  }, [device.id, initiator, persistMessage]);

  const sendMessage = useCallback(() => {
    const text = draft.trim();
    if (text.length === 0) {
      return;
    }
    setDraft('');
    NativeModule?.sendMessage(text);
    persistMessage(text, 'me');
  }, [draft, persistMessage]);

  const renderItem: ListRenderItem<Message> = ({ item }) => {
    const isMine = item.sender === 'me';
    return (
      <View
        style={[styles.messageRow, isMine ? styles.messageRowMine : styles.messageRowTheirs]}>
        <View style={[styles.bubble, isMine ? styles.bubbleMine : styles.bubbleTheirs]}>
          <Text style={[styles.messageText, isMine && styles.messageTextMine]}>
            {item.text}
          </Text>
          <Text style={[styles.messageTime, isMine && styles.messageTimeMine]}>
            {formatTime(item.timestamp)}
          </Text>
        </View>
      </View>
    );
  };

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      keyboardVerticalOffset={0}>
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <Pressable
          onPress={onBack}
          style={styles.backButton}
          accessibilityRole="button"
          accessibilityLabel="Go back">
          <Text style={styles.backIcon}>‹</Text>
        </Pressable>
        <View style={styles.headerInfo}>
          <Text style={styles.headerTitle} numberOfLines={1}>
            {device.name}
          </Text>
          <Text style={styles.headerSubtitle} numberOfLines={1}>
            {connected ? 'Connected' : 'Connecting...'}
          </Text>
        </View>
        <View style={styles.headerSpacer} />
      </View>

      <FlatList
        ref={listRef}
        data={messages}
        keyExtractor={item => item.id}
        renderItem={renderItem}
        contentContainerStyle={styles.listContent}
        onContentSizeChange={() =>
          listRef.current?.scrollToEnd({ animated: true })
        }
        ListEmptyComponent={
          <View style={styles.empty}>
            <Text style={styles.emptyTitle}>Say hello</Text>
            <Text style={styles.emptyText}>
              Send a message to start the conversation with {device.name}.
            </Text>
          </View>
        }
      />

      <View style={[styles.inputBar, { paddingBottom: insets.bottom + 10 }]}>
        <TextInput
          style={styles.input}
          value={draft}
          onChangeText={setDraft}
          placeholder="Message"
          placeholderTextColor="#8A94A6"
          multiline
          onSubmitEditing={sendMessage}
          returnKeyType="send"
        />
        <Pressable
          onPress={sendMessage}
          disabled={draft.trim().length === 0}
          style={[
            styles.sendButton,
            draft.trim().length === 0 && styles.sendButtonDisabled,
          ]}
          accessibilityRole="button"
          accessibilityLabel="Send message">
          <Text style={styles.sendButtonText}>Send</Text>
        </Pressable>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0E1116',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingBottom: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1B2330',
  },
  backButton: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  backIcon: {
    fontSize: 34,
    lineHeight: 36,
    color: '#2F80ED',
  },
  headerInfo: {
    flex: 1,
    alignItems: 'center',
  },
  headerTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: '#F5F7FA',
  },
  headerSubtitle: {
    marginTop: 2,
    fontSize: 12,
    color: '#8A94A6',
  },
  headerSpacer: {
    width: 40,
  },
  listContent: {
    flexGrow: 1,
    paddingHorizontal: 16,
    paddingVertical: 16,
  },
  messageRow: {
    marginVertical: 4,
    flexDirection: 'row',
  },
  messageRowMine: {
    justifyContent: 'flex-end',
  },
  messageRowTheirs: {
    justifyContent: 'flex-start',
  },
  bubble: {
    maxWidth: '78%',
    borderRadius: 18,
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  bubbleMine: {
    backgroundColor: '#2F80ED',
    borderBottomRightRadius: 4,
  },
  bubbleTheirs: {
    backgroundColor: '#1B2330',
    borderBottomLeftRadius: 4,
  },
  messageText: {
    fontSize: 15,
    color: '#F5F7FA',
  },
  messageTextMine: {
    color: '#FFFFFF',
  },
  messageTime: {
    marginTop: 4,
    fontSize: 10,
    color: '#8A94A6',
    alignSelf: 'flex-end',
  },
  messageTimeMine: {
    color: '#D6E4FF',
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
    paddingVertical: 48,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#F5F7FA',
  },
  emptyText: {
    marginTop: 8,
    fontSize: 14,
    lineHeight: 20,
    textAlign: 'center',
    color: '#8A94A6',
  },
  inputBar: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    paddingHorizontal: 16,
    paddingTop: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#1B2330',
  },
  input: {
    flex: 1,
    maxHeight: 120,
    minHeight: 44,
    borderRadius: 22,
    backgroundColor: '#1B2330',
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 12,
    fontSize: 15,
    color: '#F5F7FA',
  },
  sendButton: {
    marginLeft: 10,
    height: 44,
    paddingHorizontal: 20,
    borderRadius: 22,
    backgroundColor: '#2F80ED',
    alignItems: 'center',
    justifyContent: 'center',
  },
  sendButtonDisabled: {
    backgroundColor: '#1B2330',
  },
  sendButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '600',
  },
});

export default ChatScreen;
