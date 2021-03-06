package fr.pasteur.iah.swingbug;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Jama.Matrix;

public class NumPyReader
{

	private static final String NUMPY_MAGIC_STRING = "�NUMPY ";

	public static final boolean isNumPy( final String filePath )
	{
		try (final FileInputStream fileInputStream = new FileInputStream( filePath );
				final BufferedReader headerReader = new BufferedReader( new InputStreamReader( fileInputStream ) ))
		{
			final String header = headerReader.readLine();
			if ( !header.substring( 0, 8 ).equals( NUMPY_MAGIC_STRING ) )
				return false;

			final Pattern pattern = Pattern.compile( ".+'shape': \\((\\d*),\\s*(\\d*)\\),.+" );
			final Matcher m = pattern.matcher( header );
			if ( !m.matches() )
				return false;
		}
		catch ( final FileNotFoundException e )
		{
			return false;
		}
		catch ( final IOException e )
		{
			return false;
		}
		return true;

	}

	public static final double[][] readFile( final String filePath ) throws FileNotFoundException, IOException
	{
		final int nCols;
		final int nRows;

		try (final FileInputStream fileInputStream = new FileInputStream( filePath );
				final BufferedReader headerReader = new BufferedReader( new InputStreamReader( fileInputStream ) ))
		{
			final String header = headerReader.readLine();
			if ( !header.substring( 0, 8 ).equals( NUMPY_MAGIC_STRING ) )
				throw new IOException( "The file " + filePath + " is not a NumPy file." );

			final Pattern pattern = Pattern.compile( ".+'shape': \\((\\d*),\\s*(\\d*)\\),.+" );
			final Matcher m = pattern.matcher( header );
			if ( !m.matches() )
				throw new IOException( "Could not find the 'shape' descriptor in the file header." );

			// Determine shape (array or matrix).
			final String val1 = m.group( 1 );
			final String val2 = m.group( 2 );
			nRows = Integer.parseInt( val1 );
			nCols = ( val2.isEmpty() )
					? 1
					: Integer.parseInt( val2 );
		}

		try (final FileInputStream fileInputStream = new FileInputStream( filePath );
				final DataInputStream reader = new DataInputStream( new BufferedInputStream( fileInputStream ) ))
		{
			// Skip the header.
			while ( reader.readByte() != 10 )
				continue;

			// Pre-allocate output.
			final double[][] out = new double[ nCols ][ nRows ];

			// Read 'line by line'. We make a byte buffer the size of one line.
			final byte[] b = new byte[ 8 * nCols ];
			final double[] line = new double[ nCols ];

			for ( int r = 0; r < nRows; r++ )
			{
				reader.read( b );
				byte2Double( b, true, line );
				for ( int c = 0; c < line.length; c++ )
					out[ c ][ r ] = line[ c ];
			}

			return out;
		}
	}

	public static Map< Integer, Matrix > readTracks( final String trackFile ) throws FileNotFoundException, IOException
	{
		final double[][] data = readFile( trackFile );
		final Map< Integer, Matrix > tracks = new HashMap<>();

		int idx = 0;
		int trackID = ( int ) data[ 3 ][ 0 ];
		for ( int i = 0; i < data[ 0 ].length; i++ )
		{
			if ( trackID != data[ 3 ][ i ] )
			{
				// We changed track id. Backtrack to the start of it.

				final int nRows = i - idx;
				final Matrix cs = new Matrix( nRows, 2 );
				for ( int r = 0; r < nRows; r++ )
				{
					cs.set( r, 0, data[ 0 ][ i - nRows + r ] );
					cs.set( r, 1, data[ 1 ][ i - nRows + r ] );
				}
				tracks.put( Integer.valueOf( trackID ), cs );
				idx = i;
				trackID = ( int ) data[ 3 ][ i ];
			}

			if ( i == ( data[ 0 ].length - 1 ) )
			{
				final int nRows = i - idx + 1;
				final Matrix cs = new Matrix( nRows, 2 );
				for ( int r = 0; r < nRows; r++ )
				{
					cs.set( r, 0, data[ 0 ][ i - nRows + r + 1 ] );
					cs.set( r, 1, data[ 1 ][ i - nRows + r + 1 ] );
				}
				tracks.put( Integer.valueOf( trackID ), cs );
				idx = i;
				trackID = ( int ) data[ 3 ][ i ];
				tracks.put( Integer.valueOf( trackID ), cs );
			}
		}
		return tracks;
	}

	private static final void byte2Double( final byte[] inData, final boolean byteSwap, final double[] outData )
	{
		int j = 0, upper, lower;
		final int length = inData.length / 8;
		if ( !byteSwap )
			for ( int i = 0; i < length; i++ )
			{
				j = i * 8;
				upper = ( ( ( inData[ j ] & 0xff ) << 24 )
						+ ( ( inData[ j + 1 ] & 0xff ) << 16 )
						+ ( ( inData[ j + 2 ] & 0xff ) << 8 )
						+ ( ( inData[ j + 3 ] & 0xff ) << 0 ) );

				lower = ( ( ( inData[ j + 4 ] & 0xff ) << 24 )
						+ ( ( inData[ j + 5 ] & 0xff ) << 16 )
						+ ( ( inData[ j + 6 ] & 0xff ) << 8 )
						+ ( ( inData[ j + 7 ] & 0xff ) << 0 ) );

				outData[ i ] = Double.longBitsToDouble( ( ( ( long ) upper ) << 32 )
						+ ( lower & 0xffffffffl ) );
			}
		else
			for ( int i = 0; i < length; i++ )
			{
				j = i * 8;
				upper = ( ( ( inData[ j + 7 ] & 0xff ) << 24 )
						+ ( ( inData[ j + 6 ] & 0xff ) << 16 )
						+ ( ( inData[ j + 5 ] & 0xff ) << 8 )
						+ ( ( inData[ j + 4 ] & 0xff ) << 0 ) );

				lower = ( ( ( inData[ j + 3 ] & 0xff ) << 24 )
						+ ( ( inData[ j + 2 ] & 0xff ) << 16 )
						+ ( ( inData[ j + 1 ] & 0xff ) << 8 )
						+ ( ( inData[ j ] & 0xff ) << 0 ) );

				outData[ i ] = Double.longBitsToDouble( ( ( ( long ) upper ) << 32 )
						+ ( lower & 0xffffffffl ) );
			}
	}
}
