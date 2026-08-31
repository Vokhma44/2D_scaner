import test from 'node:test';
import assert from 'node:assert/strict';
import { appendQueued, removeAcknowledged, sanitizeQueue } from '../src/offline-queue.js';

const scan = (id) => ({
  id,
  code: `CODE-${id}`,
  format: 'data_matrix',
  scannedAt: 1,
  source: 'camera',
  status: 'pending',
});

test('скан остаётся в очереди до подтверждения с тем же id', () => {
  const queued = appendQueued([], scan('one'));
  assert.deepEqual(removeAcknowledged(queued, 'another'), queued);
  assert.deepEqual(removeAcknowledged(queued, 'one'), []);
});

test('при восстановлении повреждённого хранилища мусор отбрасывается', () => {
  assert.deepEqual(sanitizeQueue({ bad: true }), []);
  assert.deepEqual(sanitizeQueue([null, { id: 1 }, scan('ok')]), [scan('ok')]);
});

test('переполнение сохраняет последние сканы без превышения лимита', () => {
  let queue = [];
  for (let index = 0; index < 7; index += 1) queue = appendQueued(queue, scan(String(index)), 5);
  assert.deepEqual(queue.map((item) => item.id), ['2', '3', '4', '5', '6']);
});

test('подтверждение одного id не удаляет остальные ожидающие сканы', () => {
  const queue = [scan('a'), scan('b'), scan('c')];
  assert.deepEqual(removeAcknowledged(queue, 'b').map((item) => item.id), ['a', 'c']);
});
