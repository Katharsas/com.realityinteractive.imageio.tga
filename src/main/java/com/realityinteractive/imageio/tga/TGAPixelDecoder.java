package com.realityinteractive.imageio.tga;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.imageio.stream.ImageInputStream;

/**
 * <p>Implementation details of the decoding process.</p>
 * <p>All methods in the hot path should be static (or at least final) to improve performance.</p>
 * 
 * @author Jan Mothes
 */
public class TGAPixelDecoder {
	
	private final static byte default_alpha = (byte) 0xFF; // max value by default => fully opaque
	
	private ByteBuffer createColorMap(TGAHeader header, ImageInputStream input)
			throws IOException
	{
		input.seek(header.getColorMapDataOffset());
		
		final int numberOfColors = header.getColorMapLength();
        final int bitsPerEntry = header.getBitsPerColorMapEntry();
        final int bytesPerEntry = (bitsPerEntry + 7) / 8;
        
        final ByteBuffer inputBuffer = ByteBuffer.allocate(numberOfColors * bytesPerEntry);
        input.readFully(inputBuffer.array());
        
        final ByteBuffer colorMap = ByteBuffer.allocate(numberOfColors * 4);

        // read and convert
        for (int i = 0; i < numberOfColors; i++) {
            readAny(inputBuffer, colorMap, bytesPerEntry, true, null);
        }
        return colorMap;
	}
	
	public void read(TGAHeader header, ImageInputStream input, boolean hasAlpha, ByteBuffer dest)
			throws IOException
	{
        final int width = header.getWidth();
        final int height = header.getHeight();
        
        final boolean hasColorMap = header.hasColorMap();
        final ByteBuffer colorMap = hasColorMap ? createColorMap(header, input) : null;
        
        // check how much bytes we need to read per pixel (divide to ceiling)
        final int bytesPerInputPixel = (header.getBitsPerPixel() + 7) / 8;
        
        // check how much bytes we need to write per pixel (BGRA or BGR)
        final int bytesPerOutputPixel = hasAlpha ? 4 : 3;
        
        input.seek(header.getPixelDataOffset());
        
        // buffer size should be at least 16K and should be a multiple of 3 and 4, this allows
        // reading 24/32 bit pixels without remaining bytes during buffer refill (when no RLE)
        final int minBufferSize = 8192 * 6;
        final ByteBuffer inputBuffer = ByteBuffer.allocate(minBufferSize);
        // trigger bufferIfEmpty call to fill buffer first time
        inputBuffer.limit(0);
        
        final int bytesPerOutputRow = width * bytesPerOutputPixel;

        final boolean isRLE = header.isCompressed();
        boolean isRawMode = false;// RLE: mode is either 'raw' or 'runlength'
        int modeCounter = 0; // RLE: For how many pixels to stay in mode before reading next mode
        
        for (int y = 0; y < height; y++)
        {
        	// find correct row position in destination buffer to write to
        	final int currentRow;
            if (header.isBottomToTop())
            {
            	currentRow = (height - y) - 1;
            } else
            {
            	currentRow = y;
            }
            {
                final int index = currentRow * bytesPerOutputRow;
                dest.position(index);
                dest.limit(index + bytesPerOutputRow);
            }

            // write pixels into destination buffer
            for (int x = 0; x < width; x++)
            {
                if (isRLE)
                {
                    if (modeCounter > 0)
                    {
                        // in runLength mode, pixels are repeated
                        if (!isRawMode)
                        {
                            // Undo position change so that last pixel is read again. This is safe
                            // because we never refill buffer while counter > 0 in rawLength mode
                            // so position > 0
                            inputBuffer.position(inputBuffer.position() - bytesPerInputPixel);
                        } else {
                            checkFillBuffer(input, inputBuffer, bytesPerInputPixel);
                        }
                        modeCounter--;
                    } else
                    {
                        // Read RLE header byte.
                        // Make sure we have enough buffer for this byte and the following pixel.
                        // End of input could be reached so we need to return if we have.
                        if (checkFillBuffer(input, inputBuffer, bytesPerInputPixel + 1))
                            return;
                        
                        final byte runLengthOrRaw = inputBuffer.get();
                        isRawMode = (runLengthOrRaw & 0b10000000) == 0;
                        // bit 7 == 0 => raw mode
                        // bit 7 == 1 => runLength mode
                        modeCounter = runLengthOrRaw & 0b01111111;
                        // rest of byte (excluding bit 7) is counter

                    }
                } else
                {
                    // load more data into buffer if it has been fully consumed
                    checkFillBuffer(input, inputBuffer, bytesPerInputPixel);
                }
                // read pixel and convert to BGR(A)
                readAny(inputBuffer, dest, bytesPerInputPixel, hasAlpha, colorMap);
            }
        }
	}
	
	/**
     * @param input        image data to be put into the buffer
     * @param buffer       buffer whose entire backing array is to be filled with image data
     * @param minRemaining the refill only occurs if there are less than this remaining bytes
     *                     in the buffer.
     * @return             true if input signaled EOF and therefore no bytes could be read
     * @throws IOException if there is an I/O error while reading the input
     */
	private static boolean checkFillBuffer(ImageInputStream input, ByteBuffer buffer, int minRemaining)
	        throws IOException
	{
	    final int remaining = buffer.remaining();
        if (remaining < minRemaining)
        {
            final int bytesLoaded;
            if (remaining != 0) {
                // copy remaining bytes from end to start of buffer, then fill new data after remaining
                buffer.get(buffer.array(), 0, remaining);
                bytesLoaded = input.read(buffer.array(), remaining, buffer.capacity()-remaining);
            } else {
                // just fill entire buffer with new data
                bytesLoaded = input.read(buffer.array());
            }
            if (bytesLoaded == -1)
                return true;
            buffer.position(0);
            buffer.limit(remaining+bytesLoaded);
        }
        return false;
    }
	
	/**
     * @param bytesPerInputPixel - must be between 1 and 4 inclusive
     * @param colorMap - pass null if there is no color map
     */
	private static void readAny(ByteBuffer source, ByteBuffer dest, int bytesPerInputPixel, boolean hasAlpha, ByteBuffer colorMap) {
	    if (bytesPerInputPixel == 1)
	    {
            if (colorMap != null)
            {
                read_8b_colorMapped(source, dest, hasAlpha, colorMap);
            } else {
                read_8b(source, dest, hasAlpha);
            }
        }
        else if (bytesPerInputPixel == 2)
        {
            read_16b(source, dest, hasAlpha);
        }
        else if (bytesPerInputPixel == 3)
        {
            read_24b(source, dest, hasAlpha);
        }
        else if (bytesPerInputPixel == 4)
        {
            read_32b(source, dest, hasAlpha);
        }
        else
        {
            throw new IllegalArgumentException();
        }
	}
	
	private static void read_8b_colorMapped(ByteBuffer source, ByteBuffer dest, boolean hasDestAlpha, ByteBuffer colorMap)
	{
        if (!hasDestAlpha)
        {
            readFrom_8b_to_BGR_colorMapped(source, dest, colorMap);
        } else
        {
            readFrom_8b_to_BGRA_colorMapped(source, dest, colorMap);
        }
    }
	
	private static void read_8b(ByteBuffer source, ByteBuffer dest, boolean hasDestAlpha)
	{
        if (!hasDestAlpha)
        {
            readFrom_8b_to_BGR(source, dest);
        } else
        {
            readFrom_8b_to_BGRA(source, dest);
        }
    }
	
	private static void read_16b(ByteBuffer source, ByteBuffer dest, boolean hasDestAlpha)
	{
	    if (!hasDestAlpha)
	    {
	        readFrom_16b_to_BGR(source, dest);
        } else
        {
            readFrom_16b_to_BGRA(source, dest);
        }
	}
	
	private static void read_24b(ByteBuffer source, ByteBuffer dest, boolean hasDestAlpha)
	{
	    if (!hasDestAlpha)
	    {
	        readFrom_24b_to_BGR(source, dest);
        } else
        {
            readFrom_24b_to_BGRA(source, dest);
        }
	}
	
	private static void read_32b(ByteBuffer source, ByteBuffer dest, boolean hasDestAlpha)
	{
	    if (!hasDestAlpha)
	    {
	        throw new UnsupportedOperationException("Discarding alpha channel is not supported.");
	    }
	    readFrom_32b_to_BGRA(source, dest);
	}
	
	/**
	 * Copy 3 bytes (BGR) from color map
	 */
    private static void readFrom_8b_to_BGR_colorMapped(ByteBuffer source, ByteBuffer dest, ByteBuffer colorMap)
    {
    	final int mapIndex = source.get();
    	dest.put(colorMap.array(), mapIndex * 4, 3);
    }
    
    /**
     * Copy 4 bytes (BGRA) from color map
     */
    private static void readFrom_8b_to_BGRA_colorMapped(ByteBuffer source, ByteBuffer dest, ByteBuffer colorMap)
    {
    	final int mapIndex = source.get();
    	dest.put(colorMap.array(), mapIndex * 4, 4);
    }
    
    /**
     * Copy 1 byte 3 times (BGR)
     */
    private static void readFrom_8b_to_BGR(ByteBuffer source, ByteBuffer dest)
    {
        final byte grayScaleValue = source.get(); // unsigned
        // b = g = r
        dest.put(grayScaleValue);
        dest.put(grayScaleValue);
        dest.put(grayScaleValue);
    }
    
    /**
     * Copy 1 byte 3 times (BGR) and default alpha
     */
    private static void readFrom_8b_to_BGRA(ByteBuffer source, ByteBuffer dest)
    {
        // blue = green = red
    	readFrom_8b_to_BGR(source, dest);
    	dest.put(default_alpha);
    }
	
    /**
     * Get 3 components (BGR) from 15 bits and put
     */
    private static void readFrom_16b_to_BGR(ByteBuffer source, ByteBuffer dest)
    {
    	// read B-G-R as 5-5-5 bits, discard last bit
        final int data = source.getShort() & 0xFFFF; // unsigned
        dest.put((byte) ((data       ) << 3));
        dest.put((byte) ((data >>>  5) << 3));
        dest.put((byte) ((data >>> 10) << 3));
    }
    
    /**
     * Get 4 components (BGRA) from 16 bits and put
     */
    private static void readFrom_16b_to_BGRA(ByteBuffer source, ByteBuffer dest)
    {
    	// read B-G-R-A as 5-5-5-1 bits
        final int data = source.getShort() & 0xFFFF; // unsigned
        dest.put((byte) ((data       ) << 3));
        dest.put((byte) ((data >>>  5) << 3));
        dest.put((byte) ((data >>> 10) << 3));
    	dest.put((byte) (data >>> 15));
    }
    
    /**
     * Copy 3 bytes (BGR)
     */
    private static void readFrom_24b_to_BGR(ByteBuffer source, ByteBuffer dest) {
        final int index = dest.position();
    	source.get(dest.array(), index, 3);
    	dest.position(index + 3);
    }
    
    /**
     * Copy 3 bytes (BGR) and default alpha
     */
    private static void readFrom_24b_to_BGRA(ByteBuffer source, ByteBuffer dest) {
        readFrom_24b_to_BGR(source, dest);
    	dest.put(default_alpha);
    }
    
    /**
     * Copy 4 bytes (BGRA)
     */
    private static void readFrom_32b_to_BGRA(ByteBuffer source, ByteBuffer dest) {
        final int index = dest.position();
    	source.get(dest.array(), index, 4);
    	dest.position(index + 4);
    }
}
