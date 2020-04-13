package com.jessewo.libcopyexif;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Created by Jessewoo on 2018/11/29.
 * <p>
 * A class for parsing the exif orientation and other data from an image header.
 * <a href="http://www.cppblog.com/lymons/archive/2010/02/23/108266.aspx">Exif文件格式描述</a>
 * <p>
 * --------------------------------------------------------------------------------------------------------------------------
 * | SOI 标记 | 标记 XX 的大小=SSSS          | 标记 YY 的大小=TTTT          | SOS 标记 的大小=UUUU   | 图像数据流     | EOI 标记
 * --------------------------------------------------------------------------------------------------------------------------
 * | FFD8    | FFXX	SSSS	DDDD......	   | FFYY	TTTT	DDDD......	 | FFDA	UUUU DDDD....   | I I I I....	| FFD9
 * --------------------------------------------------------------------------------------------------------------------------
 */
public class ImageHeaderParser {
    private static final String TAG = "ImageHeaderParser";
    private static final boolean debug = true;

    private int magicNumber;

    /**
     * The format of the image data including whether or not the image may include transparent pixels.
     */
    public enum ImageType {
        /**
         * GIF type.
         */
        GIF(true),
        /**
         * JPG type.
         */
        JPEG(false),
        /**
         * PNG type with alpha.
         */
        PNG_A(true),
        /**
         * PNG type without alpha.
         */
        PNG(false),
        /**
         * Unrecognized type.
         */
        UNKNOWN(false);
        private final boolean hasAlpha;

        ImageType(boolean hasAlpha) {
            this.hasAlpha = hasAlpha;
        }

        public boolean hasAlpha() {
            return hasAlpha;
        }
    }

    private static final int GIF_HEADER = 0x474946;
    private static final int PNG_HEADER = 0x89504E47;
    //JPEG 起始标记字节
    private static final int EXIF_MAGIC_NUMBER = 0xFFD8;
    // "MM".
    private static final int MOTOROLA_TIFF_MAGIC_NUMBER = 0x4D4D;
    // "II".
    private static final int INTEL_TIFF_MAGIC_NUMBER = 0x4949;
    // EXIF数据内容的第一部分
    private static final String JPEG_EXIF_SEGMENT_PREAMBLE = "Exif\0\0";
    private static final byte[] JPEG_EXIF_SEGMENT_PREAMBLE_BYTES;
    //JPEG SOS数据流的起始(Start of stream) 标记
    private static final int SEGMENT_SOS = 0xDA;
    //JPEG 图像结束标记字节
    private static final int MARKER_EOI = 0xD9;

    private static final int SEGMENT_START_ID = 0xFF;
    //EXIF用的APP1标记位, 其后两个字节记录 EXIF 的大小(需要-2)
    private static final int EXIF_SEGMENT_TYPE = 0xE1;
    private static final int ORIENTATION_TAG_TYPE = 0x0112;
    private static final int[] BYTES_PER_FORMAT = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};

    private final StreamReader streamReader;
    private ImageType imageType;
    private byte[] exifBlock;
    private int exifStartIndex;

    static {
        byte[] bytes = new byte[0];
        try {
            bytes = JPEG_EXIF_SEGMENT_PREAMBLE.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Ignore.
        }
        JPEG_EXIF_SEGMENT_PREAMBLE_BYTES = bytes;
    }

    public ImageHeaderParser(byte[] data) {
        this(new ByteArrayInputStream(data));
    }

    public ImageHeaderParser(InputStream is) {
        streamReader = new StreamReader(is);
        parse();
    }

    private void parse() {
        try {
            final int magicNumber = streamReader.getUInt16();
            this.magicNumber = magicNumber;
            ImageType imageType;
            // JPEG.
            if (magicNumber == EXIF_MAGIC_NUMBER) {
                imageType = ImageType.JPEG;
                parseExifBlock();
            } else {
                final int firstFourBytes = magicNumber << 16 & 0xFFFF0000 | streamReader.getUInt16() & 0xFFFF;
                // PNG.
                if (firstFourBytes == PNG_HEADER) {
                    // See: http://stackoverflow.com/questions/2057923/how-to-check-a-png-for-grayscale-alpha-color-type
                    streamReader.skip(25 - 4);
                    int alpha = streamReader.getByte();
                    // A RGB indexed PNG can also have transparency. Better safe than sorry!
                    imageType = alpha >= 3 ? ImageType.PNG_A : ImageType.PNG;
                } else if (firstFourBytes >> 8 == GIF_HEADER) {
                    // GIF from first 3 bytes.
                    imageType = ImageType.GIF;
                } else {
                    imageType = ImageType.UNKNOWN;
                }
            }
            this.imageType = imageType;


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 0xD0A3C68 -> <htm
    // 0xCAFEBABE -> <!DOCTYPE...
    public boolean hasAlpha() {
        return getType().hasAlpha();
    }

    public ImageType getType() {
        return this.imageType;
    }

    public byte[] getExifBlock() {
        return this.exifBlock;
    }

    public byte[] getExifContent() {
        return this.exifBlock != null && this.exifBlock.length > 4 ? Arrays.copyOfRange(this.exifBlock, 4, this.exifBlock.length - 1) : null;
    }

    public int getExifStartIndex() {
        return this.exifStartIndex;
    }

    /**
     * 将原图片中的EXIF复制到目标图片中
     * 仅限JPEG
     *
     * @param srcData
     * @param destData
     * @return
     */
    public static byte[] cloneExif(byte[] srcData, byte[] destData) {
        if (srcData == null || srcData.length == 0 || destData == null || destData.length == 0)
            return null;

        ImageHeaderParser srcImageHeaderParser = new ImageHeaderParser(srcData);
        byte[] srcExifBlock = srcImageHeaderParser.getExifBlock();
        if (srcExifBlock == null || srcExifBlock.length <= 4) return destData;

        if (debug)
            LOG.d(TAG, "pictureData src: %1$s KB; dest: %2$s KB", srcData.length / 1024, destData.length / 1024);
        if (debug) LOG.d(TAG, "srcExif: %s B", srcExifBlock.length);
        ImageHeaderParser destImageHeaderParser = new ImageHeaderParser(destData);
        byte[] destExifBlock = destImageHeaderParser.getExifBlock();
        if (destExifBlock != null && destExifBlock.length > 0) {
            if (debug) LOG.d(TAG, "destExif: %s B", destExifBlock.length);
            //目标图片中已有exif信息, 需要先删除
            int exifStartIndex = destImageHeaderParser.getExifStartIndex();
            //构建新数组
            byte[] newDestData = new byte[srcExifBlock.length + destData.length - destExifBlock.length];
            //copy 1st block
            System.arraycopy(destData, 0, newDestData, 0, exifStartIndex);
            //copy 2rd block (exif)
            System.arraycopy(srcExifBlock, 0, newDestData, exifStartIndex, srcExifBlock.length);
            //copy 3th block
            int srcPos = exifStartIndex + destExifBlock.length;
            int destPos = exifStartIndex + srcExifBlock.length;
            System.arraycopy(destData, srcPos, newDestData, destPos, destData.length - srcPos);
            if (debug) LOG.d(TAG, "output image Data with exif: %s KB", newDestData.length / 1024);
            return newDestData;
        } else {
            if (debug) LOG.d(TAG, "destExif: %s B", 0);
            //目标图片中没有exif信息
            byte[] newDestData = new byte[srcExifBlock.length + destData.length];
            //copy 1st block (前两个字节)
            System.arraycopy(destData, 0, newDestData, 0, 2);
            //copy 2rd block (exif)
            System.arraycopy(srcExifBlock, 0, newDestData, 2, srcExifBlock.length);
            //copy 3th block
            int srcPos = 2;
            int destPos = 2 + srcExifBlock.length;
            System.arraycopy(destData, srcPos, newDestData, destPos, destData.length - srcPos);
            if (debug) LOG.d(TAG, "output image Data with exif: %s KB", newDestData.length / 1024);
            return newDestData;
        }

    }

    private void parseExifBlock() throws IOException {
        short segmentId, segmentType;
        int segmentLength;
        int index = 2;
        while (true) {
            segmentId = streamReader.getUInt8();

            if (segmentId != SEGMENT_START_ID) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Unknown segmentId=" + segmentId);
                }
                return;
            }

            segmentType = streamReader.getUInt8();

            if (segmentType == SEGMENT_SOS) {
                return;
            } else if (segmentType == MARKER_EOI) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Found MARKER_EOI in exif segment");
                }
                return;
            }

            // Segment length includes bytes for segment length.
            segmentLength = streamReader.getUInt16() - 2;

            if (segmentType != EXIF_SEGMENT_TYPE) {
                //跳过所有的非exif标记块
                long skipped = streamReader.skip(segmentLength);
                if (skipped != segmentLength) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Unable to skip enough data"
                                + ", type: " + segmentType
                                + ", wanted to skip: " + segmentLength
                                + ", but actually skipped: " + skipped);
                    }
                    return;
                }
                index += (4 + segmentLength);
            } else {
                //找到exif block
                byte[] segmentData = new byte[segmentLength];
                int read = streamReader.read(segmentData);

                byte[] block = new byte[2 + 2 + read];
                block[0] = (byte) SEGMENT_START_ID;
                block[1] = (byte) EXIF_SEGMENT_TYPE;
                int length = read + 2;
                block[2] = (byte) ((length >> 8) & 0xFF);
                block[3] = (byte) (length & 0xFF);

                if (read != segmentLength) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Unable to read segment data"
                                + ", type: " + segmentType
                                + ", length: " + segmentLength
                                + ", actually read: " + read);
                    }
                } else {
                    System.arraycopy(segmentData, 0, block, 4, read);
                }
                this.exifBlock = block;
                this.exifStartIndex = index;
                return;
            }
        }
    }

    /**
     * Parse the orientation from the image header. If it doesn't handle this image type (or this is not an image)
     * it will return a default value rather than throwing an exception.
     *
     * @return The exif orientation if present or -1 if the header couldn't be parsed or doesn't contain an orientation
     * @throws IOException
     */
    public int getOrientation() {
        if (!handles(this.magicNumber)) {
            return -1;
        } else {
            byte[] exifData = getExifContent();
            boolean hasJpegExifPreamble = exifData != null
                    && exifData.length > JPEG_EXIF_SEGMENT_PREAMBLE_BYTES.length;

            if (hasJpegExifPreamble) {
                for (int i = 0; i < JPEG_EXIF_SEGMENT_PREAMBLE_BYTES.length; i++) {
                    if (exifData[i] != JPEG_EXIF_SEGMENT_PREAMBLE_BYTES[i]) {
                        hasJpegExifPreamble = false;
                        break;
                    }
                }
            }

            if (hasJpegExifPreamble) {
                return parseExifSegment(new RandomAccessReader(exifData));
            } else {
                return -1;
            }
        }
    }

    private static int parseExifSegment(RandomAccessReader segmentData) {
        final int headerOffsetSize = JPEG_EXIF_SEGMENT_PREAMBLE.length();

        short byteOrderIdentifier = segmentData.getInt16(headerOffsetSize);
        final ByteOrder byteOrder;
        if (byteOrderIdentifier == MOTOROLA_TIFF_MAGIC_NUMBER) {
            byteOrder = ByteOrder.BIG_ENDIAN;
        } else if (byteOrderIdentifier == INTEL_TIFF_MAGIC_NUMBER) {
            byteOrder = ByteOrder.LITTLE_ENDIAN;
        } else {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unknown endianness = " + byteOrderIdentifier);
            }
            byteOrder = ByteOrder.BIG_ENDIAN;
        }

        segmentData.order(byteOrder);

        int firstIfdOffset = segmentData.getInt32(headerOffsetSize + 4) + headerOffsetSize;
        int tagCount = segmentData.getInt16(firstIfdOffset);

        int tagOffset, tagType, formatCode, componentCount;
        for (int i = 0; i < tagCount; i++) {
            tagOffset = calcTagOffset(firstIfdOffset, i);

            tagType = segmentData.getInt16(tagOffset);

            // We only want orientation.
            if (tagType != ORIENTATION_TAG_TYPE) {
                continue;
            }

            formatCode = segmentData.getInt16(tagOffset + 2);

            // 12 is max format code.
            if (formatCode < 1 || formatCode > 12) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Got invalid format code=" + formatCode);
                }
                continue;
            }

            componentCount = segmentData.getInt32(tagOffset + 4);

            if (componentCount < 0) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Negative tiff component count");
                }
                continue;
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Got tagIndex=" + i + " tagType=" + tagType + " formatCode=" + formatCode
                        + " componentCount=" + componentCount);
            }

            final int byteCount = componentCount + BYTES_PER_FORMAT[formatCode];

            if (byteCount > 4) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Got byte count > 4, not orientation, continuing, formatCode=" + formatCode);
                }
                continue;
            }

            final int tagValueOffset = tagOffset + 8;

            if (tagValueOffset < 0 || tagValueOffset > segmentData.length()) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Illegal tagValueOffset=" + tagValueOffset + " tagType=" + tagType);
                }
                continue;
            }

            if (byteCount < 0 || tagValueOffset + byteCount > segmentData.length()) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Illegal number of bytes for TI tag data tagType=" + tagType);
                }
                continue;
            }

            //assume componentCount == 1 && fmtCode == 3
            return segmentData.getInt16(tagValueOffset);
        }

        return -1;
    }

    private static int calcTagOffset(int ifdOffset, int tagIndex) {
        return ifdOffset + 2 + 12 * tagIndex;
    }

    private static boolean handles(int imageMagicNumber) {
        return (imageMagicNumber & EXIF_MAGIC_NUMBER) == EXIF_MAGIC_NUMBER
                || imageMagicNumber == MOTOROLA_TIFF_MAGIC_NUMBER
                || imageMagicNumber == INTEL_TIFF_MAGIC_NUMBER;
    }

    private static class RandomAccessReader {
        private final ByteBuffer data;

        public RandomAccessReader(byte[] data) {
            this.data = ByteBuffer.wrap(data);
            this.data.order(ByteOrder.BIG_ENDIAN);
        }

        public void order(ByteOrder byteOrder) {
            this.data.order(byteOrder);
        }

        public int length() {
            return data.array().length;
        }

        public int getInt32(int offset) {
            return data.getInt(offset);
        }

        public short getInt16(int offset) {
            return data.getShort(offset);
        }
    }

    private static class StreamReader {
        private final InputStream is;
        //motorola / big endian byte order

        public StreamReader(InputStream is) {
            this.is = is;
        }

        /**
         * 读两个字节
         *
         * @return
         * @throws IOException
         */
        public int getUInt16() throws IOException {
            return (is.read() << 8 & 0xFF00) | (is.read() & 0xFF);
        }

        /**
         * 读一个字节
         *
         * @return
         * @throws IOException
         */
        public short getUInt8() throws IOException {
            return (short) (is.read() & 0xFF);
        }

        public long skip(long total) throws IOException {
            if (total < 0) {
                return 0;
            }

            long toSkip = total;
            while (toSkip > 0) {
                long skipped = is.skip(toSkip);
                if (skipped > 0) {
                    toSkip -= skipped;
                } else {
                    // Skip has no specific contract as to what happens when you reach the end of
                    // the stream. To differentiate between temporarily not having more data and
                    // having finished the stream, we read a single byte when we fail to skip any
                    // amount of data.
                    int testEofByte = is.read();
                    if (testEofByte == -1) {
                        break;
                    } else {
                        toSkip--;
                    }
                }
            }
            return total - toSkip;
        }

        public int read(byte[] buffer) throws IOException {
            int toRead = buffer.length;
            int read;
            while (toRead > 0 && ((read = is.read(buffer, buffer.length - toRead, toRead)) != -1)) {
                toRead -= read;
            }
            return buffer.length - toRead;
        }

        public int getByte() throws IOException {
            return is.read();
        }
    }
}

