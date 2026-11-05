package com.crbyob.bridge;

/** Encodes one Y8 IR frame for an RTSP session (IrJpeg: ir_jpeg_over_y8 test mode; RleCodec: OB_FMT_RLE). */
interface IrEncoder {
    /** Encodes the frame; the output is buf()[0..return value). */
    int encode(byte[] y8);

    byte[] buf();

    /** {frames encoded, bytes out, ns spent} for stream_stats. */
    long[] stats();
}
