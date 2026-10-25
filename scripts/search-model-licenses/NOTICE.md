# Bundled search engine

Dougle runs these models locally for retrieval, not answer generation.

- intfloat/multilingual-e5-small, MIT: https://huggingface.co/intfloat/multilingual-e5-small
  Revision: 614241f622f53c4eeff9890bdc4f31cfecc418b3. Upstream E5: https://github.com/microsoft/unilm/tree/master/e5
- cross-encoder/mmarco-mMiniLMv2-L12-H384-v1, Apache-2.0: https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1
  Revision: 1427fd652930e4ba29e8149678df786c240d8825. Upstream: https://github.com/UKPLab/sentence-transformers

The upstream quantized ONNX models and tokenizers are distributed without modification.
MIT.txt and Apache-2.0.txt contain the upstream license texts.
