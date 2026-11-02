import test from 'node:test';
import assert from 'node:assert/strict';
import { toPurchaseOrderRequest } from '../src/utils/purchaseOrderPayload.js';

test('purchase-order full request preserves header and details in the backend contract', () => {
  const source = { supplierId: 4, warehouseId: 1, version: 2, details: [{ materialId: 9, orderQuantity: 3 }] };
  const result = toPurchaseOrderRequest(source);
  assert.equal(result.purchaseOrder.supplierId, 4);
  assert.equal(result.purchaseOrder.warehouseId, 1);
  assert.equal(result.purchaseOrder.version, 2);
  assert.deepEqual(result.details, [{ materialId: 9, orderQuantity: 3 }]);
  assert.equal('details' in result.purchaseOrder, false);
  assert.equal(source.details.length, 1);
});
