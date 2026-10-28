import test from 'node:test'
import assert from 'node:assert/strict'
import { verifyBrand } from './verify-brand.mjs'
const config = { productName: 'Dougle', identifier: 'dev.dougle.desktop', version: '0.1.32', bundle: { resources: ['resources/dougle.jar'] } }
const desktop = { name: 'dougle-desktop', version: '0.1.32' }
test('active product identity agrees', () => verifyBrand(config, desktop))
for (const [field, wrong] of [['productName', 'Old Product'], ['identifier', 'dev.old.desktop'], ['version', '0.1.31']]) {
  test(`rejects stale ${field}`, () => assert.throws(() => verifyBrand({ ...config, [field]: wrong }, desktop)))
}
test('rejects a stale resource name', () => assert.throws(() => verifyBrand({ ...config, bundle: { resources: ['resources/old.jar'] } }, desktop)))
