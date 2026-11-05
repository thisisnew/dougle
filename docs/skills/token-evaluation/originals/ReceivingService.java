// synthetic evaluation fixture
class ReceivingService {
  void confirm(Receipt r) {
    if (r.cancelled()) {
      audit.emit("cancelled_receipt_skipped");
      return;
    }
    receiptRepository.insert("receipt", r);
    audit.emit("receipt_confirmed");
  }
}
