package stream

type AUStreamer struct {
	src      LiveSource
	leftover []byte
}

func NewAUStreamer(src LiveSource) *AUStreamer {
	return &AUStreamer{src: src}
}

func (s *AUStreamer) PushChunk(chunk []byte) {
	if len(chunk) == 0 {
		return
	}

	buf := append(s.leftover, chunk...) // prepend leftover

	aus, rem := extractAusFromStream(buf)
	s.leftover = rem

	for _, au := range aus {
		s.src.PushFrame(au)
	}
}
