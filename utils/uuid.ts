const HEX = '0123456789abcdef';

const randomByte = (): number => {
  const cryptoObject = (globalThis as { crypto?: { getRandomValues?: (array: Uint8Array) => void } })
    .crypto;
  if (cryptoObject?.getRandomValues) {
    const bytes = new Uint8Array(1);
    cryptoObject.getRandomValues(bytes);
    return bytes[0];
  }
  return Math.floor(Math.random() * 256);
};

export const createUuid = (): string => {
  const bytes = new Uint8Array(16);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = randomByte();
  }

  bytes[6] = (bytes[6] % 16) + 64;
  bytes[8] = (bytes[8] % 64) + 128;

  let result = '';
  for (let index = 0; index < bytes.length; index += 1) {
    result += HEX[Math.floor(bytes[index] / 16)] + HEX[bytes[index] % 16];
    if (index === 3 || index === 5 || index === 7 || index === 9) {
      result += '-';
    }
  }
  return result;
};
