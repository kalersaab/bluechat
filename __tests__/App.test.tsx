/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock('@op-engineering/op-sqlite', () => ({
  open: () => ({
    execute: async () => ({ rows: [], rowsAffected: 0, insertId: 1 }),
  }),
}));

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
