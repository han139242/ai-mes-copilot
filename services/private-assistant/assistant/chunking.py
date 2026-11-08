import json


def located_chunks(tokenizer, content, split_by_character=None, split_by_character_only=False,
                   chunk_overlap_token_size=30, chunk_token_size=240):
    """Every chunk repeats its real locator, including continuation chunks of long paragraphs."""
    pieces = json.loads(content)
    chunks = []
    for piece in pieces:
        tokens = tokenizer.encode(piece["text"])
        prefix = f"【来源定位：{piece['locator']}】\n"
        available = max(20, chunk_token_size-len(tokenizer.encode(prefix)))
        overlap = min(chunk_overlap_token_size, available//4)
        for start in range(0, len(tokens), available-overlap):
            text = prefix + tokenizer.decode(tokens[start:start+available])
            chunks.append({"tokens": len(tokenizer.encode(text)), "content": text,
                           "chunk_order_index": len(chunks)})
    return chunks
