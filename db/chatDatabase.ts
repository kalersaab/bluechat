import { open, type DB, type Scalar } from '@op-engineering/op-sqlite';
import type {
  ChatMessage,
  ChatSender,
  Conversation,
  MessageStatus,
} from '../interface';

type MessageRow = {
  message_id: string;
  text: string;
  sender: string;
  timestamp: number;
  status: string;
};

let database: DB | null = null;
let ready: Promise<DB> | null = null;

const CHAT_SENDERS: ChatSender[] = ['me', 'them'];
const MESSAGE_STATUSES: MessageStatus[] = [
  'pending',
  'sent',
  'delivered',
  'read',
  'failed',
];

const isChatSender = (value: string): value is ChatSender =>
  CHAT_SENDERS.includes(value as ChatSender);

const isMessageStatus = (value: string): value is MessageStatus =>
  MESSAGE_STATUSES.includes(value as MessageStatus);

const toChatMessage = (row: MessageRow): ChatMessage => ({
  messageId: row.message_id,
  text: row.text,
  sender: isChatSender(row.sender) ? row.sender : 'them',
  timestamp: row.timestamp,
  status: isMessageStatus(row.status) ? row.status : 'sent',
});

const toMessageRow = (row: Record<string, Scalar>): MessageRow | null => {
  const { message_id, text, sender, timestamp, status } = row;
  if (
    typeof message_id !== 'string' ||
    typeof text !== 'string' ||
    typeof sender !== 'string' ||
    typeof timestamp !== 'number' ||
    typeof status !== 'string'
  ) {
    return null;
  }
  return { message_id, text, sender, timestamp, status };
};

const CREATE_MESSAGES_TABLE = `
  CREATE TABLE IF NOT EXISTS messages (
    message_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    sender TEXT NOT NULL,
    text TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    status TEXT NOT NULL
  )
`;

async function tableExists(db: DB, name: string): Promise<boolean> {
  const { rows } = await db.execute(
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
    [name],
  );
  return rows.length > 0;
}

async function columnNames(db: DB, table: string): Promise<string[]> {
  const { rows } = await db.execute(`PRAGMA table_info(${table})`);
  return rows.map(row => String(row.name));
}

async function rebuildMessagesTable(db: DB): Promise<void> {
  await db.execute(`
    CREATE TABLE messages_new (
      message_id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL,
      sender TEXT NOT NULL,
      text TEXT NOT NULL,
      timestamp INTEGER NOT NULL,
      status TEXT NOT NULL
    )
  `);
  await db.execute(`
    INSERT INTO messages_new (message_id, device_id, sender, text, timestamp, status)
    SELECT
      lower(hex(randomblob(16))),
      device_id,
      sender,
      text,
      timestamp,
      'sent'
    FROM messages
  `);
  await db.execute('DROP TABLE messages');
  await db.execute('ALTER TABLE messages_new RENAME TO messages');
}

async function initDatabase(db: DB): Promise<void> {
  if (!(await tableExists(db, 'messages'))) {
    await db.execute(CREATE_MESSAGES_TABLE);
  } else {
    const columns = await columnNames(db, 'messages');
    if (!columns.includes('message_id')) {
      await rebuildMessagesTable(db);
    }
  }

  await db.execute(`
    CREATE INDEX IF NOT EXISTS idx_messages_device_timestamp
    ON messages (device_id, timestamp)
  `);
  await db.execute(`
    CREATE TABLE IF NOT EXISTS contacts (
      device_id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      updated_at INTEGER NOT NULL
    )
  `);
}

async function getDatabase(): Promise<DB> {
  if (database) {
    return database;
  }
  if (!ready) {
    ready = (async () => {
      const db = open({ name: 'bluechat.sqlite' });
      await initDatabase(db);
      database = db;
      return db;
    })().catch(error => {
      ready = null;
      throw error;
    });
  }
  return ready;
}

export async function upsertContact(params: {
  deviceId: string;
  name: string;
}): Promise<void> {
  const db = await getDatabase();
  await db.execute(
    `
      INSERT INTO contacts (device_id, name, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(device_id) DO UPDATE SET
        name = excluded.name,
        updated_at = excluded.updated_at
    `,
    [params.deviceId, params.name, Date.now()],
  );
}

export async function getConversations(): Promise<Conversation[]> {
  const db = await getDatabase();
  const { rows } = await db.execute(`
    SELECT
      m.device_id AS deviceId,
      COALESCE(c.name, m.device_id) AS name,
      m.text AS lastMessage,
      m.timestamp AS lastTimestamp
    FROM messages m
    LEFT JOIN contacts c ON c.device_id = m.device_id
    WHERE m.message_id = (
      SELECT message_id FROM messages
      WHERE device_id = m.device_id
      ORDER BY timestamp DESC, message_id DESC
      LIMIT 1
    )
    ORDER BY m.timestamp DESC, m.message_id DESC
  `);
  return rows.flatMap(row => {
    const { deviceId, name, lastMessage, lastTimestamp } = row;
    if (
      typeof deviceId !== 'string' ||
      typeof name !== 'string' ||
      typeof lastMessage !== 'string' ||
      typeof lastTimestamp !== 'number'
    ) {
      return [];
    }
    return [{ deviceId, name, lastMessage, lastTimestamp }];
  });
}

export async function getMessagesForDevice(
  deviceId: string,
): Promise<ChatMessage[]> {
  const db = await getDatabase();
  const { rows } = await db.execute(
    `
      SELECT message_id, text, sender, timestamp, status
      FROM messages
      WHERE device_id = ?
      ORDER BY timestamp ASC, message_id ASC
    `,
    [deviceId],
  );
  return rows.flatMap(row => {
    const parsed = toMessageRow(row);
    return parsed ? [toChatMessage(parsed)] : [];
  });
}

export async function saveMessage(params: {
  deviceId: string;
  messageId: string;
  text: string;
  sender: ChatSender;
  timestamp: number;
  status: MessageStatus;
}): Promise<ChatMessage> {
  const db = await getDatabase();
  await db.execute(
    `
      INSERT INTO messages (message_id, device_id, sender, text, timestamp, status)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(message_id) DO UPDATE SET
        sender = excluded.sender,
        text = excluded.text,
        timestamp = excluded.timestamp,
        status = excluded.status
    `,
    [
      params.messageId,
      params.deviceId,
      params.sender,
      params.text,
      params.timestamp,
      params.status,
    ],
  );

  return {
    messageId: params.messageId,
    text: params.text,
    sender: params.sender,
    timestamp: params.timestamp,
    status: params.status,
  };
}

export async function updateMessageStatus(
  messageId: string,
  status: MessageStatus,
): Promise<void> {
  const db = await getDatabase();
  await db.execute('UPDATE messages SET status = ? WHERE message_id = ?', [
    status,
    messageId,
  ]);
}
