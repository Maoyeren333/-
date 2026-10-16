import { test } from 'node:test'
import assert from 'node:assert/strict'
import { imageName, selectVisibleResult, resultOptions } from '../src/utils/interpretation.js'

test('selection can move from the first image to a different image', () => {
  const items = [{ id: 2, sourcePath: 'second.jpg', status: 'SUCCESS' }, { id: 1, sourcePath: 'first.jpg', status: 'SUCCESS' }]
  assert.equal(selectVisibleResult(items, 1).sourcePath, 'first.jpg')
  assert.equal(selectVisibleResult(items, 2).sourcePath, 'second.jpg')
})

test('refresh replaces stale RUNNING metadata with current SUCCESS data', () => {
  const current = { id: 2, status: 'SUCCESS', sourcePath: 'second.jpg', maskPath: 'second.tif' }
  assert.equal(selectVisibleResult([current], 2), current)
})

test('changing pages or filters discards a result outside the visible records', () => {
  const page = [{ id: 20, status: 'SUCCESS', sourcePath: 'other-page.jpg' }]
  assert.equal(selectVisibleResult(page, 1), page[0])
  assert.equal(selectVisibleResult([], 1), null)
})

test('default preview prefers successful output while explicit selection preserves failure status', () => {
  const items = [{ id: 3, status: 'FAILED' }, { id: 2, status: 'SUCCESS' }]
  assert.equal(selectVisibleResult(items).id, 2)
  assert.equal(selectVisibleResult(items, 3).status, 'FAILED')
})

test('direct preview of an older export is still offered in the selector', () => {
  const current = { id: 2 }
  assert.deepEqual(resultOptions([{ id: 10 }], current).map(item => item.id), [2, 10])
  assert.deepEqual(resultOptions([current], current), [current])
})

test('filenames display correctly for Windows paths and Unix paths', () => {
  assert.equal(imageName('C:\\images\\second.jpg'), 'second.jpg')
  assert.equal(imageName('/images/second.jpg'), 'second.jpg')
})
