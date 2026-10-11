export type ChatSender = 'me' | 'them';

export type MessageStatus =
  | 'pending'
  | 'sent'
  | 'delivered'
  | 'read'
  | 'failed';

export type ChatMessage = {
  messageId: string;
  text: string;
  sender: ChatSender;
  timestamp: number;
  status: MessageStatus;
};

export type Conversation = {
  deviceId: string;
  name: string;
  lastMessage: string;
  lastTimestamp: number;
};
