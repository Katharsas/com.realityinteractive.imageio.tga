package com.realityinteractive.imageio.tga;

/*
 * TGAImageReader.java
 * Copyright (c) 2003 Reality Interactive, Inc.  
 *   See bottom of file for license and warranty information.
 * Created on Sep 26, 2003
 */

import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * <p>The {@link ImageReader} that exposes the TGA image reading.
 * 8, 15, 16, 24 and 32 bit true color or color mapped (RLE compressed or 
 * uncompressed) are supported.  Monochrome images are not supported.</p>
 * 
 * <p>Great care should be employed with {@link ImageReadParam}s.
 * Little to no effort has been made to correctly handle sub-sampling or 
 * specified bands.</p> 
 * 
 * <p>{@link javax.imageio.ImageIO#setUseCache(boolean)} should be set to <code>false</code>
 * when using this reader.  Also, {@link javax.imageio.ImageIO#read(java.io.InputStream)}
 * is the preferred read method if used against a buffered array (for performance
 * reasons).</p>
 * 
 * @author Rob Grzywinski <a href="mailto:rgrzywinski@realityinteractive.com">rgrzywinski@realityinteractive.com</a>
 * @version $Id: TGAImageReader.java,v 1.1 2005/04/12 11:23:53 ornedan Exp $
 * @since 1.0
 */
// TODO:  incorporate the x and y origins
public class TGAImageReader2 extends ImageReader
{
    /**
     * <p>The {@link ImageInputStream} from which the TGA
     * is read.  This may be <code>null</code> if {@link ImageReader#setInput(Object)}
     * (or the other forms of <code>setInput()</code>) has not been called.  The
     * stream will be set litle-endian when it is set.</p>
     */
    private ImageInputStream inputStream;

    /**
     * <p>The {@link TGAHeader}.  If <code>null</code>
     * then the header has not been read since <code>inputStream</code> was 
     * last set.  This is created lazily.</p>
     */
    private TGAHeader header;

    // =========================================================================
    /**
     * @see ImageReader#ImageReader(ImageReaderSpi)
     */
    public TGAImageReader2(final ImageReaderSpi originatingProvider)
    {
        super(originatingProvider);
    }

    /**
     * <p>Store the input if it is an {@link ImageInputStream}.
     * Otherwise {@link IllegalArgumentException} is thrown.  The
     * stream is set to little-endian byte ordering.</p>  
     * 
     * @see ImageReader#setInput(Object, boolean, boolean)
     */
    // NOTE:  can't read the header in here as there would be no place for
    //        exceptions to go.  It must be read lazily.
    @Override
    public void setInput(final Object input, final boolean seekForwardOnly,
                         final boolean ignoreMetadata)
    {
        // delegate to the parent
        super.setInput(input, seekForwardOnly, ignoreMetadata);

        // if the input is null clear the inputStream and header
        if(input == null)
        {
            inputStream = null;
            header = null;
        } /* else -- the input is non-null */

        // only ImageInputStream are allowed.  If other throw IllegalArgumentException
        if(input instanceof ImageInputStream)
        {
            // set the inputStream
            inputStream = (ImageInputStream)input;

            // put the ImageInputStream into little-endian ("Intel byte ordering")
            // byte ordering
            inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);

        } else /* input is not an instance of ImageInputStream */
        {
            throw new IllegalArgumentException("Only ImageInputStreams are accepted.");  // FIXME:  localize
        }
    }

    /**
     * <p>Create and read the {@link TGAHeader}
     * only if there is not one already.</p>
     * 
     * @return the <code>TGAHeader</code> (for convenience)
     * @throws IOException if there is an I/O error while reading the header
     */
    private synchronized TGAHeader getHeader()
        throws IOException
    {
        // if there is already a header (non-null) then there is nothing to be
        // done
        if(header != null)
            return header;
        /* else -- there is no header */

        // ensure that there is an ImageInputStream from which the header is
        // read
        if(inputStream == null)
            throw new IllegalStateException("There is no ImageInputStream from which the header can be read."); // FIXME:  localize
        /* else -- there is an input stream */

        header = new TGAHeader(inputStream);

        return header;
    }

    /**
     * <p>Only a single image can be read by this reader.  Validate the 
     * specified image index and if not <code>0</code> then {@link IndexOutOfBoundsException}
     * is thrown.</p>
     * 
     * @param  imageIndex the index of the image to validate
     * @throws IndexOutOfBoundsException if the <code>imageIndex</code> is not
     *         <code>0</code>
     */
    private void checkImageIndex(final int imageIndex)
    {
        // if the imageIndex is not 0 then throw an exception
        if(imageIndex != 0)
            throw new IndexOutOfBoundsException("Image index out of bounds (" + imageIndex + " != 0)."); // FIXME:  localize
        /* else -- the index is in bounds */
    }

    // =========================================================================
    // Required ImageReader methods
    /**
     * @see ImageReader#getImageTypes(int)
     */
    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(final int imageIndex) 
        throws IOException
    {
        // validate the imageIndex (this will throw if invalid)
        checkImageIndex(imageIndex);

        // read / get the header
        final TGAHeader header = getHeader();

        // get the ImageTypeSpecifier for the image type
        // FIXME:  finish
        final ImageTypeSpecifier imageTypeSpecifier;
        switch(header.getImageType())
        {
            case TGAConstants.COLOR_MAP:
            case TGAConstants.RLE_COLOR_MAP:
            case TGAConstants.TRUE_COLOR:
            case TGAConstants.RLE_TRUE_COLOR:
            {
                // determine if there is an alpha mask based on the number of
                // samples per pixel
                final boolean hasAlpha = header.getSamplesPerPixel() == 4;
                
                // define order of R, G, B, A bands
                // BGR(A) is the only order can be read directly by OpenCV library, so use it
                final int[] bandOffset;
                if(hasAlpha)
                	bandOffset = new int[] {2, 1, 0, 3};// BGRA
                else
                    bandOffset = new int[] {2, 1, 0};// BGR
                
                
                // interleaved BGR(A) pixel data, some space gets wasted for lower bit depths
                final ColorSpace rgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                imageTypeSpecifier = ImageTypeSpecifier.createInterleaved(
                        rgb,
                        bandOffset,
                        DataBuffer.TYPE_BYTE,
                        hasAlpha,
                        false /*not pre-multiplied by an alpha*/);
            
                break;
            }

            case TGAConstants.MONO:
            case TGAConstants.RLE_MONO:
                throw new IllegalArgumentException("Monochrome image type not supported.");
            
            case TGAConstants.NO_IMAGE:
            default:
                throw new IllegalArgumentException("The image type is not known."); // FIXME:  localize
        }

        // create a list and add the ImageTypeSpecifier to it
        final List<ImageTypeSpecifier> imageSpecifiers = new ArrayList<ImageTypeSpecifier>();
        imageSpecifiers.add(imageTypeSpecifier);

        return imageSpecifiers.iterator();
    }

    /**
     * <p>Only a single image is supported.</p>
     * 
     * @see ImageReader#getNumImages(boolean)
     */
    @Override
    public int getNumImages(final boolean allowSearch) 
        throws IOException
    {
        // see javadoc
        // NOTE:  1 is returned regardless if a search is allowed or not
        return 1;
    }

    /**
     * <p>There is no stream metadata (i.e.  <code>null</code> is returned).</p>
     * 
     * @see ImageReader#getStreamMetadata()
     */
    @Override
    public IIOMetadata getStreamMetadata() 
        throws IOException
    {
        // see javadoc
        return null;
    }

    /**
     * <p>There is no image metadata (i.e.  <code>null</code> is returned).</p>
     * 
     * @see ImageReader#getImageMetadata(int)
     */
    @Override
    public IIOMetadata getImageMetadata(final int imageIndex) 
        throws IOException
    {
        // see javadoc
        return null;
    }

    /**
     * @see ImageReader#getHeight(int)
     */
    @Override
    public int getHeight(final int imageIndex) 
        throws IOException
    {
        // validate the imageIndex (this will throw if invalid)
        checkImageIndex(imageIndex);

        // get the header and return the height
        return getHeader().getHeight();
    }

    /**
     * @see ImageReader#getWidth(int)
     */
    @Override
    public int getWidth(final int imageIndex) 
        throws IOException
    {
        // validate the imageIndex (this will throw if invalid)
        checkImageIndex(imageIndex);

        // get the header and return the width
        return getHeader().getWidth();
    }

    /**
     * @see ImageReader#read(int, ImageReadParam)
     */
    @Override
    public BufferedImage read(final int imageIndex, final ImageReadParam param)
        throws IOException
    {
        // ensure that the image is of a supported type
        // NOTE:  this will implicitly ensure that the imageIndex is valid
        final Iterator<ImageTypeSpecifier> imageTypes = getImageTypes(imageIndex);
        if(!imageTypes.hasNext())
        {
            throw new IOException("Unsupported Image Type");
        }

        // read and get the header
        final TGAHeader header = getHeader();

		// ensure that the ImageReadParam hasn't been set to other than the
		// defaults (this will throw if not acceptable)
		checkImageReadParam(param, header);

		// get the height and width from the header for convenience
		final int width = header.getWidth();
		final int height = header.getHeight();
        

        // get the destination image and WritableRaster for the image type and 
        // size
        final BufferedImage image = getDestination(param, imageTypes, 
                                                   width, height);
        final WritableRaster imageRaster = image.getRaster();         

        // get and validate the number of image bands
        final int numberOfImageBands = image.getSampleModel().getNumBands();
        checkReadParamBandSettings(param, header.getSamplesPerPixel(), 
                                          numberOfImageBands);

        // get the destination bands
        final int[] destinationBands;
        if(param != null)
        {
            // there is an ImageReadParam -- use its destination bands
            destinationBands = param.getDestinationBands();
        } else
        {
            // there are no destination bands
            destinationBands = null;
        }
        
        final boolean hasAlpha = image.getColorModel().hasAlpha();

        // create the destination WritableRaster
        final WritableRaster raster = imageRaster.createWritableChild(0, 0, 
                                                                      width,
                                                                      height, 
                                                                      0, 0,
                                                                      destinationBands);
        
        // get destination buffer
        final byte[] resultData = ((DataBufferByte)raster.getDataBuffer()).getData(); // CHECK:  is this valid / acceptible?
        final ByteBuffer resultBuffer = ByteBuffer.wrap(resultData);
        
        // decode into destination buffer
        final TGAPixelDecoder decoder = new TGAPixelDecoder();
        decoder.read(header, inputStream, hasAlpha, resultBuffer);
        
        // make sure we have all the data
        if (resultBuffer.hasRemaining()) {
            throw new IllegalStateException("Not enough data could be read into buffer.");
        }
        
        return image;
    }
    

    /**
     * <p>Validate that the specified {@link ImageReadParam}
     * contains only the default values.  If non-default values are present,
     * {@link IOException} is thrown.</p>
     * 
     * @param  param the <code>ImageReadParam</code> to be validated
     * @param  header the <code>TGAHeader</code> that contains information about
     *         the source image
     * @throws IOException if the <code>ImageReadParam</code> contains non-default
     *         values
     */
    private void checkImageReadParam(final ImageReadParam param,
                                     final TGAHeader header)
        throws IOException
    {
        if(param != null)
        {
            // get the image height and width from the header for convenience
            final int width = header.getWidth();
            final int height = header.getHeight();
            
            // ensure that the param contains only the defaults
            final Rectangle sourceROI = param.getSourceRegion();
            if( (sourceROI != null) && 
                ( (sourceROI.x != 0) || (sourceROI.y != 0) ||
                  (sourceROI.width != width) || (sourceROI.height != height) ) )
            {
                throw new IOException("The source region of interest is not the default."); // FIXME:  localize
            } /* else -- the source ROI is the default */

            final Rectangle destinationROI = param.getSourceRegion();
            if( (destinationROI != null) &&
                ( (destinationROI.x != 0) || (destinationROI.y != 0) ||
                  (destinationROI.width != width) || (destinationROI.height != height) ) )
            {
                throw new IOException("The destination region of interest is not the default."); // FIXME:  localize
            } /* else -- the destination ROI is the default */

            if( (param.getSourceXSubsampling() != 1) || 
                (param.getSourceYSubsampling() != 1) )
            {
                throw new IOException("Source sub-sampling is not supported."); // FIXME:  localize
            } /* else -- sub-sampling is the default */
        } /* else -- the ImageReadParam is null so the defaults *are* used */
    }
}
// =============================================================================
/*
    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */