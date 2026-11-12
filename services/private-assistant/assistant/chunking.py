import json


def located_chunks(tokenizer, content, split_by_character=None, split_by_character_only=False,
                   chunk_overlap_token_size=30, chunk_token_size=240, embedding_tokenizer=None):
    """Pack adjacent pieces while retaining a real locator for every source span.

    Respect both the extraction tokenizer and the embedding model's tokenizer.
    A conservative character bound is used when no embedding tokenizer is supplied.
    """
    pieces = json.loads(content)
    chunks, buffer = [], ''
    limit = min(chunk_token_size, 480)
    def fits(text):
        embedding_fits = (len(embedding_tokenizer.encode(text).ids) <= 512 if embedding_tokenizer
                          else len(text) <= limit)
        return embedding_fits and len(tokenizer.encode(text)) <= limit
    def append(text):
        chunks.append({'tokens':len(tokenizer.encode(text)), 'content':text,
                       'chunk_order_index':len(chunks)})
    for piece in pieces:
        original = piece['text']
        prefix = f"【来源定位：{piece['locator']}】\n"
        start = 0
        while start < len(original):
            # Slice original Unicode characters, never arbitrary tokenizer bytes.
            low, high, length = 1, min(len(original)-start, limit*8), 0
            while low <= high:
                middle = (low+high)//2
                if fits(prefix+original[start:start+middle]):
                    length = middle; low = middle+1
                else: high = middle-1
            if not length: raise ValueError('来源定位过长，无法构建可追溯片段')
            text = prefix+original[start:start+length]
            candidate = buffer+'\n\n'+text if buffer else text
            if buffer and not fits(candidate):
                append(buffer); buffer = text
            else: buffer = candidate
            if start+length >= len(original): break
            overlap = min(chunk_overlap_token_size, length//4)
            while overlap and len(tokenizer.encode(original[start+length-overlap:start+length]))>chunk_overlap_token_size:
                overlap -= 1
            start += length-overlap
    if buffer: append(buffer)
    return chunks
