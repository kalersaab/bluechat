import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  ListRenderItem,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { getConversations } from '../db/chatDatabase';
import type { Conversation, HomeScreenProps } from '../interface';

const Separator = () => <View style={styles.separator} />;

const formatTimestamp = (timestamp: number): string => {
  const date = new Date(timestamp);
  const now = new Date();
  const isToday =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();

  if (isToday) {
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    return `${hours}:${minutes}`;
  }

  const day = date.getDate().toString().padStart(2, '0');
  const month = (date.getMonth() + 1).toString().padStart(2, '0');
  return `${day}/${month}`;
};

const getInitials = (name: string): string => {
  const trimmed = name.trim();
  if (trimmed.length === 0) {
    return '?';
  }
  return trimmed.slice(0, 2).toUpperCase();
};

function HomeScreen({ onNewChat, onOpenChat }: HomeScreenProps) {
  const insets = useSafeAreaInsets();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    getConversations()
      .then(items => {
        if (!cancelled) {
          setConversations(items);
        }
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const renderItem: ListRenderItem<Conversation> = useCallback(
    ({ item }) => (
      <Pressable
        style={styles.row}
        onPress={() => onOpenChat(item.deviceId)}
        accessibilityRole="button">
        <View style={styles.avatar}>
          <Text style={styles.avatarText}>{getInitials(item.name)}</Text>
        </View>
        <View style={styles.rowInfo}>
          <View style={styles.rowTop}>
            <Text style={styles.name} numberOfLines={1}>
              {item.name}
            </Text>
            <Text style={styles.time}>
              {formatTimestamp(item.lastTimestamp)}
            </Text>
          </View>
          <Text style={styles.preview} numberOfLines={1}>
            {item.lastMessage}
          </Text>
        </View>
      </Pressable>
    ),
    [onOpenChat],
  );

  return (
    <View style={[styles.container, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <View style={styles.headerText}>
          <Text style={styles.title}>Chats</Text>
          <Text style={styles.subtitle}>
            {conversations.length === 0
              ? 'Your conversations will appear here'
              : `${conversations.length} conversation${
                  conversations.length === 1 ? '' : 's'
                }`}
          </Text>
        </View>
        <Pressable
          style={styles.newButton}
          onPress={onNewChat}
          accessibilityRole="button"
          accessibilityLabel="Start a new chat">
          <Text style={styles.newButtonText}>+</Text>
        </Pressable>
      </View>

      {loading ? (
        <View style={styles.empty}>
          <ActivityIndicator size="large" color="#2F80ED" />
        </View>
      ) : (
        <FlatList
          data={conversations}
          keyExtractor={item => item.deviceId}
          renderItem={renderItem}
          contentContainerStyle={styles.listContent}
          ItemSeparatorComponent={Separator}
          ListEmptyComponent={
            <View style={styles.empty}>
              <Text style={styles.emptyTitle}>No chats yet</Text>
              <Text style={styles.emptyText}>
                Find someone nearby and start a conversation.
              </Text>
              <Pressable style={styles.emptyButton} onPress={onNewChat}>
                <Text style={styles.emptyButtonText}>Start a new chat</Text>
              </Pressable>
            </View>
          }
        />
      )}
    </View>
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
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 12,
  },
  headerText: {
    flex: 1,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: '#F5F7FA',
  },
  subtitle: {
    marginTop: 4,
    fontSize: 14,
    color: '#8A94A6',
  },
  newButton: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: '#2F80ED',
    alignItems: 'center',
    justifyContent: 'center',
  },
  newButtonText: {
    color: '#FFFFFF',
    fontSize: 28,
    lineHeight: 32,
    fontWeight: '600',
  },
  listContent: {
    paddingHorizontal: 20,
    flexGrow: 1,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
  },
  avatar: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: '#1B2330',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    color: '#2F80ED',
    fontWeight: '700',
    fontSize: 16,
  },
  rowInfo: {
    flex: 1,
    marginLeft: 14,
  },
  rowTop: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  name: {
    flex: 1,
    fontSize: 16,
    fontWeight: '600',
    color: '#F5F7FA',
  },
  time: {
    marginLeft: 8,
    fontSize: 12,
    color: '#8A94A6',
  },
  preview: {
    marginTop: 3,
    fontSize: 14,
    color: '#8A94A6',
  },
  separator: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: '#1B2330',
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
  emptyButton: {
    marginTop: 20,
    backgroundColor: '#2F80ED',
    borderRadius: 14,
    paddingVertical: 12,
    paddingHorizontal: 24,
  },
  emptyButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '600',
  },
});

export default HomeScreen;
