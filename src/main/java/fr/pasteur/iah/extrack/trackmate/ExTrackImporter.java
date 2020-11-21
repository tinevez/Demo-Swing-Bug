package fr.pasteur.iah.extrack.trackmate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import org.jgrapht.graph.DefaultWeightedEdge;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.features.edges.EdgeAnalyzer;
import fiji.plugin.trackmate.features.spot.SpotAnalyzerFactory;
import fiji.plugin.trackmate.features.track.TrackAnalyzer;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.gui.descriptors.ConfigureViewsDescriptor;
import fiji.plugin.trackmate.providers.EdgeAnalyzerProvider;
import fiji.plugin.trackmate.providers.SpotAnalyzerProvider;
import fiji.plugin.trackmate.providers.TrackAnalyzerProvider;
import fiji.plugin.trackmate.visualization.PerEdgeFeatureColorGenerator;
import fiji.plugin.trackmate.visualization.SpotColorGenerator;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import fr.pasteur.iah.swingbug.NumPyReader;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.algorithm.OutputAlgorithm;

public class ExTrackImporter implements OutputAlgorithm< TrackMate >
{

	public static final String PLUGIN_NAME = "TrackMate-ExTrackImporter";

	private static final int X_COLUMN = 0;

	private static final int Y_COLUMN = 1;

	private static final int FRAME_COLUMN = 2;

	private static final int TRACKID_COLUMN = 3;

	private static final int PROBA_STUCK_COLUMN = 4;

	private static final int PROBA_DIFFUSIVE_COLUMN = 5;

	protected String errorMessage;

	protected final String imageFilePath;

	protected final String dataFilePath;

	private final double radius;

	private final double frameInterval;

	private final String spaceUnits;

	private final String timeUnits;

	private TrackMate trackmate;


	public ExTrackImporter(
			final String imageFilePath,
			final String dataFilePath,
			final double radius,
			final String spaceUnits,
			final double frameInterval,
			final String timeUnits )
	{
		this.imageFilePath = imageFilePath;
		this.dataFilePath = dataFilePath;
		this.radius = radius;
		this.spaceUnits = spaceUnits;
		this.frameInterval = frameInterval;
		this.timeUnits = timeUnits;
	}

	@Override
	public boolean checkInput()
	{
		final File imageFile = new File( imageFilePath );
		if ( !imageFile.exists() )
		{
			errorMessage = "Image file " + imageFilePath + " does not exist.";
			return false;
		}
		if ( !imageFile.canRead() )
		{
			errorMessage = "Image file " + imageFilePath + " cannot be opened.";
			return false;
		}

		final File dataFile = new File( dataFilePath );
		if ( !dataFile.exists() )
		{
			errorMessage = "Data file " + dataFilePath + " does not exist.";
			return false;
		}
		if ( !dataFile.canRead() )
		{
			errorMessage = "Data file " + dataFilePath + " cannot be opened.";
			return false;
		}
		if (!NumPyReader.isNumPy( dataFilePath ))
		{
			errorMessage = "Data file " + dataFilePath + " does not seem to be a NumPy file.";
			return false;
		}

		return true;
	}

	@Override
	public boolean process()
	{
		/*
		 * Load image file and create settings object.
		 */

		final Settings settings = createSettings( imageFilePath );

		/*
		 * Create model from data file.
		 */
		try
		{
			final Model model = createModel( dataFilePath, radius );
			model.setPhysicalUnits( settings.imp.getCalibration().getUnit(), settings.imp.getCalibration().getTimeUnit() );

			// Put it in TrackMate.
			this.trackmate = new TrackMate( model, settings );
			trackmate.computeSpotFeatures( false );
			trackmate.computeEdgeFeatures( false );
			trackmate.computeTrackFeatures( false );
		}
		catch ( final IOException e )
		{
			errorMessage = e.getMessage();
			return false;
		}
		return true;
	}

	@Override
	public TrackMate getResult()
	{
		return trackmate;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	protected Settings createSettings( final String imageFile )
	{
		final ImagePlus imp = IJ.openImage( imageFile );

		final Settings settings = new Settings();
		settings.setFrom( imp );

		// Declare all features.
		final SpotAnalyzerProvider spotAnalyzerProvider = new SpotAnalyzerProvider();
		final List< String > spotAnalyzerKeys = spotAnalyzerProvider.getKeys();
		for ( final String key : spotAnalyzerKeys )
		{
			final SpotAnalyzerFactory< ? > spotFeatureAnalyzer = spotAnalyzerProvider.getFactory( key );
			settings.addSpotAnalyzerFactory( spotFeatureAnalyzer );
		}

		settings.clearEdgeAnalyzers();
		final EdgeAnalyzerProvider edgeAnalyzerProvider = new EdgeAnalyzerProvider();
		final List< String > edgeAnalyzerKeys = edgeAnalyzerProvider.getKeys();
		for ( final String key : edgeAnalyzerKeys )
		{
			final EdgeAnalyzer edgeAnalyzer = edgeAnalyzerProvider.getFactory( key );
			settings.addEdgeAnalyzer( edgeAnalyzer );
		}

		settings.clearTrackAnalyzers();
		final TrackAnalyzerProvider trackAnalyzerProvider = new TrackAnalyzerProvider();
		final List< String > trackAnalyzerKeys = trackAnalyzerProvider.getKeys();
		for ( final String key : trackAnalyzerKeys )
		{
			final TrackAnalyzer trackAnalyzer = trackAnalyzerProvider.getFactory( key );
			settings.addTrackAnalyzer( trackAnalyzer );
		}

		// ExTrack features.
		settings.addSpotAnalyzerFactory( new ExTrackProbabilitiesFeature<>() );
		settings.addEdgeAnalyzer( new ExTrackEdgeFeatures() );
		settings.addTrackAnalyzer( new ExTrackTrackInfo() );

		return settings;
	}

	protected Model createModel( final String dataFile, final double radius ) throws FileNotFoundException, IOException
	{
		// Read NumPy file.
		final double[][] data = NumPyReader.readFile( dataFile );

		// Get all track IDs.
		final int[] trackIDs = DoubleStream.of( data[ TRACKID_COLUMN ] )
				.distinct()
				.mapToInt( d -> ( int ) d )
				.toArray();

		final Model model = new Model();
		model.setPhysicalUnits( spaceUnits, timeUnits );

		final double quality = 1.; // Dummy value?

		model.beginUpdate();
		try
		{
			// Loop over track IDs.
			for ( final int trackID : trackIDs )
			{
				final int[] trackRows = IntStream.range( 0, data[ TRACKID_COLUMN ].length )
						.filter( i -> data[ TRACKID_COLUMN ][ i ] == trackID )
						.toArray();
				Arrays.sort( trackRows );

				final List< Spot > spots = new ArrayList<>( trackRows.length );
				int t = 0;
				for ( final int r : trackRows )
				{
					final double x = data[ X_COLUMN ][ r ];
					final double y = data[ Y_COLUMN ][ r ];
					final double z = 0.; // No Z?
					final Spot spot = new Spot( x, y, z, radius, quality );

					/*
					 * If we have a 3rd column, use it as time column, if not
					 * take detections in order they are stored.
					 */
					final int frame;
					if ( data.length > 2 )
					{
						frame = ( int ) data[ FRAME_COLUMN ][ r ] - 1;

						/*
						 * If we have a 4th column -> proba stuck.
						 */

						if ( data.length > 4 )
						{
							final double probaStuck = data[ PROBA_STUCK_COLUMN ][ r ];
							spot.putFeature( ExTrackProbabilitiesFeature.P_STUCK, Double.valueOf( probaStuck ) );
							if ( data.length > 5 )
							{
								final double probaDiffusive = data[ PROBA_DIFFUSIVE_COLUMN ][ r ];
								spot.putFeature( ExTrackProbabilitiesFeature.P_DIFFUSIVE, Double.valueOf( probaDiffusive ) );
							}
						}
					}
					else
					{
						frame = t++;
					}
					spot.putFeature( Spot.POSITION_T, frameInterval * frame );
					model.addSpotTo( spot, Integer.valueOf( frame ) );
					spots.add( spot );

				}
				spots.sort( Spot.frameComparator );
				Spot source = spots.get( 0 );
				for ( int j = 1; j < spots.size(); j++ )
				{
					final Spot target = spots.get( j );

					final DefaultWeightedEdge edge = model.addEdge( source, target, 1. );

					final Double pStuckTarget = source.getFeature( ExTrackProbabilitiesFeature.P_STUCK );
					if ( pStuckTarget != null )
					{
						model.getFeatureModel().putEdgeFeature(
								edge,
								ExTrackEdgeFeatures.P_STUCK,
								Double.valueOf( pStuckTarget ) );
					}

					final Double pStuckDiffusive = source.getFeature( ExTrackProbabilitiesFeature.P_DIFFUSIVE );
					if ( pStuckDiffusive != null )
					{
						model.getFeatureModel().putEdgeFeature(
								edge,
								ExTrackEdgeFeatures.P_DIFFUSIVE,
								Double.valueOf( pStuckDiffusive ) );
					}

					source = target;
				}

				// Store original track ID.
				model.getFeatureModel().putTrackFeature(
						model.getTrackModel().trackIDOf( source ),
						ExTrackTrackInfo.EXTRACK_TRACKID,
						Double.valueOf( trackID ) );

			}
		}
		finally
		{
			model.endUpdate();
		}

		return model;
	}


	public static void main( final String[] args )
	{
		final String imageFile = "samples/img.tif";
//		final String dataFile = "samples/tracks.npy";
		final String dataFile = "samples/sim_tracks.npy";
		final double radius = 0.25;
		final double frameInterval = 0.1;

		final ExTrackImporter importer = new ExTrackImporter( imageFile, dataFile, radius, "µm", frameInterval, "s" );
		if (!importer.checkInput() || !importer.process())
		{
			System.err.println( "Could not import ExTrack data:" );
			System.err.println( importer.getErrorMessage() );
			return;
		}

		final TrackMate trackmate = importer.getResult();

		/*
		 * Launch controller.
		 */

		final TrackMateGUIController controller = new TrackMateGUIController( trackmate );
		controller.setGUIStateString( ConfigureViewsDescriptor.KEY );
		GuiUtils.positionWindow( controller.getGUI(), trackmate.getSettings().imp.getWindow() );

		final Model model = trackmate.getModel();

		ImageJ.main( args );

		final SelectionModel selectionModel = new SelectionModel( model );
//			final HyperStackDisplayer view = new HyperStackDisplayer( model, selectionModel, settings.imp );
		final HyperStackDisplayer view = new HyperStackDisplayer( model, selectionModel );
		final SpotColorGenerator spotColorGenerator = new SpotColorGenerator( model );
		spotColorGenerator.setFeature( ExTrackProbabilitiesFeature.P_STUCK );
		final PerEdgeFeatureColorGenerator trackColorGenerator = new PerEdgeFeatureColorGenerator( model, ExTrackEdgeFeatures.P_STUCK );
		view.setDisplaySettings( TrackMateModelView.KEY_SPOT_COLORING, spotColorGenerator );
		view.setDisplaySettings( TrackMateModelView.KEY_TRACK_COLORING, trackColorGenerator );
		view.setDisplaySettings( TrackMateModelView.KEY_TRACK_DISPLAY_MODE, TrackMateModelView.TRACK_DISPLAY_MODE_LOCAL );
		view.setDisplaySettings( TrackMateModelView.KEY_TRACK_DISPLAY_DEPTH, 20 );
		view.render();
	}
}
