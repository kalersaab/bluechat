import { open, type DB, type Scalar } from '@op-engineering/op-sqlite';

export type ChatSender = 'me' | 'them';

export type ChatMessage = {
  id: string;
  text: string;
  sender: ChatSender;
  timestamp: number;
};

type MessageRow = {
  id: number;
  text: string;
  sender: string;
  timestamp: number;
};

let database: DB | null = null;
let ready: Promise<DB> | null = null;

const isChatSender = (value: string): value is ChatSender =>
  value === 'me' || value === 'them';

const toChatMessage = (row: MessageRow): ChatMessage => ({
  id: String(row.id),
  text: row.text,
  sender: isChatSender(row.sender) ? row.sender : 'them',
  timestamp: row.timestamp,
});

const toMessageRow = (row: Record<string, Scalar>): MessageRow | null => {
  const { id, text, sender, timestamp } = row;
  if (
    typeof id !== 'number' ||
    typeof text !== 'string' ||
    typeof sender !== 'string' ||
    typeof timestamp !== 'number'
  ) {
    return null;
  }
  return { id, text, sender, timestamp };
};

async function getDatabase(): Promise<DB> {
  if (database) {
    return database;
  }
  if (!ready) {
    ready = (async () => {
      const db = open({ name: 'bluechat.sqlite' });
      await db.execute(`
        CREATE TABLE IF NOT EXISTS messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          device_id TEXT NOT NULL,
          text TEXT NOT NULL,
          sender TEXT NOT NULL,
          timestamp INTEGER NOT NULL
        )
      `);
      await db.execute(`
        CREATE INDEX IF NOT EXISTS idx_messages_device_timestamp
        ON messages (device_id, timestamp)
      `);
      database = db;
      return db;
    })().catch(error => {
      ready = null;
      throw error;
    });
  }
  return ready;
}

export async function getMessagesForDevice(
  deviceId: string,
): Promise<ChatMessage[]> {
  const db = await getDatabase();
  const { rows } = await db.execute(
    `
      SELECT id, text, sender, timestamp
      FROM messages
      WHERE device_id = ?
      ORDER BY timestamp ASC, id ASC
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
  text: string;
  sender: ChatSender;
  timestamp: number;
}): Promise<ChatMessage> {
  const db = await getDatabase();
  const result = await db.execute(
    `
      INSERT INTO messages (device_id, text, sender, timestamp)
      VALUES (?, ?, ?, ?)
    `,
    [params.deviceId, params.text, params.sender, params.timestamp],
  );

  const insertedId = result.insertId;
  if (typeof insertedId === 'number') {
    return {
      id: String(insertedId),
      text: params.text,
      sender: params.sender,
      timestamp: params.timestamp,
    };
  }

  const { rows } = await db.execute(
    `
      SELECT id, text, sender, timestamp
      FROM messages
      WHERE device_id = ? AND timestamp = ? AND sender = ? AND text = ?
      ORDER BY id DESC
      LIMIT 1
    `,
    [params.deviceId, params.timestamp, params.sender, params.text],
  );
  const row = toMessageRow(rows[0] ?? {});
  if (!row) {
    throw new Error('Failed to persist chat message');
  }
  return toChatMessage(row);
}
