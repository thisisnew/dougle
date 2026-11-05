class ReceivingService {
 void confirm(Receipt r) {
  if (r.cancelled || r.returnOrder) return;
  receipt.insert(r);
 }
 void cancel(Receipt r) { receiptCancel.insert(r.id); }
}
