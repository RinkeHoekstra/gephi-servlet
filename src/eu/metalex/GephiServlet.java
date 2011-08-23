package eu.metalex;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
 
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.gephi.data.attributes.api.AttributeColumn;
import org.gephi.data.attributes.api.AttributeController;
import org.gephi.data.attributes.api.AttributeModel;
import org.gephi.graph.api.DirectedGraph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.io.exporter.api.ExportController;
import org.gephi.io.exporter.preview.PDFExporter;
import org.gephi.io.importer.api.Container;
import org.gephi.io.importer.api.EdgeDefault;
import org.gephi.io.importer.api.ImportController;
import org.gephi.io.importer.plugin.file.ImporterPajek;
import org.gephi.io.processor.plugin.DefaultProcessor;
import org.gephi.layout.plugin.force.StepDisplacement;
import org.gephi.layout.plugin.force.yifanHu.YifanHuLayout;
import org.gephi.layout.plugin.labelAdjust.LabelAdjust;
import org.gephi.preview.api.PreviewController;
import org.gephi.preview.api.PreviewModel;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.ranking.api.ColorTransformer;
import org.gephi.ranking.api.NodeRanking;
import org.gephi.ranking.api.RankingController;
import org.gephi.ranking.api.SizeTransformer;
import org.gephi.statistics.plugin.GraphDistance;
import org.openide.util.Lookup;

import com.itextpdf.text.PageSize;


public class GephiServlet extends HttpServlet {

	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
	}
	
	
	
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException {
		String uri = request.getParameter("uri");
		
		try {
			ExportController ec = gimmeGephi(uri);
			
			
			response.setContentType("application/pdf");
			
			
			ServletOutputStream output = response.getOutputStream();
		    try { 
		    	PDFExporter pdfExporter = (PDFExporter) ec.getExporter("pdf");
		    	pdfExporter.setPageSize(PageSize.A0);
		    	ec.exportStream(output, pdfExporter);
		    	
		    }
		    finally {
		      //close all streams
		      output.close();
		    }

			return;
			
		} catch (Exception e) {
			System.out.println(uri + "is not a valid URI, or could not load Pajek file from URI.");
		}
	}
	
	public ExportController gimmeGephi(String uri_string) throws URISyntaxException, MalformedURLException {
		
		URL uri = new URL(uri_string);
		//Init a project - and therefore a workspace
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
        pc.newProject();
        Workspace workspace = pc.getCurrentWorkspace();

        //Get controllers and models
        ImportController importController = Lookup.getDefault().lookup(ImportController.class);
        GraphModel graphModel = Lookup.getDefault().lookup(GraphController.class).getModel();
        AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();

        //Import file
        Container container;
        try {
        	InputStream in = uri.openStream();
        	BufferedReader dis = new BufferedReader(new InputStreamReader(in));
        	
//        	File file = new File(getClass().getResource(uri_string).toURI());
            container = importController.importFile(dis, new ImporterPajek());
            container.getLoader().setEdgeDefault(EdgeDefault.DIRECTED);   //Force DIRECTED
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        //Append imported data to GraphAPI
        importController.process(container, new DefaultProcessor(), workspace);

        //See if graph is well imported
        DirectedGraph graph = graphModel.getDirectedGraph();
//        System.out.println("Nodes: " + graph.getNodeCount());
//        System.out.println("Edges: " + graph.getEdgeCount());

        //Rank color by InDegree
        RankingController rankingController = Lookup.getDefault().lookup(RankingController.class);
        NodeRanking degreeRanking = rankingController.getRankingModel().getInDegreeRanking();
        ColorTransformer colorTransformer = rankingController.getObjectColorTransformer(degreeRanking);
        colorTransformer.setColors(new Color[]{new Color(0xFEF0D9), new Color(0xB30000)});
        rankingController.transform(colorTransformer);

        //Get Centrality
        GraphDistance distance = new GraphDistance();
        distance.setDirected(true);
        distance.execute(graphModel, attributeModel);

        //Rank size by centrality
        AttributeColumn centralityColumn = attributeModel.getNodeTable().getColumn(GraphDistance.BETWEENNESS);
        NodeRanking centralityRanking = rankingController.getRankingModel().getNodeAttributeRanking(centralityColumn);
        SizeTransformer sizeTransformer = rankingController.getObjectSizeTransformer(centralityRanking);
        sizeTransformer.setMinSize(3);
        sizeTransformer.setMaxSize(20);
        rankingController.transform(sizeTransformer);

        //Rank label size - set a multiplier size
        NodeRanking centralityRanking2 = rankingController.getRankingModel().getNodeAttributeRanking(centralityColumn);
        SizeTransformer labelSizeTransformer = rankingController.getLabelSizeTransformer(centralityRanking2);
        labelSizeTransformer.setMinSize(1);
        labelSizeTransformer.setMaxSize(3);
        rankingController.transform(labelSizeTransformer);
        
        //Run YifanHuLayout for 100 passes - The layout always takes the current visible view
        YifanHuLayout layout = new YifanHuLayout(null, new StepDisplacement(1f));
        layout.setGraphModel(graphModel);
        layout.resetPropertiesValues();
        layout.setOptimalDistance(200f);
         
        for (int i = 0; i < 100 && layout.canAlgo(); i++) {
           layout.goAlgo();
        }
        
        //Run YifanHuLayout for 100 passes - The layout always takes the current visible view
        LabelAdjust llayout = new LabelAdjust(null);
        llayout.setGraphModel(graphModel);
         
        for (int i = 0; i < 100 && llayout.canAlgo(); i++) {
           llayout.goAlgo();
        }

        //Set 'show labels' option in Preview - and disable node size influence on text size
        PreviewModel previewModel = Lookup.getDefault().lookup(PreviewController.class).getModel();
        previewModel.getNodeSupervisor().setShowNodeLabels(Boolean.TRUE);
        previewModel.getNodeSupervisor().setProportionalLabelSize(Boolean.FALSE);

        //Export
        ExportController ec = Lookup.getDefault().lookup(ExportController.class);
        
        return ec;

	}
	
	public void destroy() {
		
	}

	public static void main(String[] args) {
		GephiServlet gs = new GephiServlet();
		
		try {
			ExportController ec = gs.gimmeGephi("http://doc.metalex.eu/doc/BWBR0017869/2009-10-23/data.net");
			
			try {
				   ec.exportFile(new File("simple.pdf"));
			} catch (IOException ex) {
				   ex.printStackTrace();
				   return;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
