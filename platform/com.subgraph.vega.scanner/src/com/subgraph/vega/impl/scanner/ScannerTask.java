package com.subgraph.vega.impl.scanner;

import java.net.URI;
import java.util.logging.Logger;

import com.subgraph.vega.api.crawler.ICrawlerConfig;
import com.subgraph.vega.api.crawler.ICrawlerEventHandler;
import com.subgraph.vega.api.crawler.IWebCrawler;
import com.subgraph.vega.api.http.requests.IHttpRequestEngine;
import com.subgraph.vega.api.http.requests.IHttpResponseProcessor;
import com.subgraph.vega.api.scanner.IScanner.ScannerStatus;
import com.subgraph.vega.api.scanner.IScannerConfig;
import com.subgraph.vega.api.scanner.model.IScanDirectory;
import com.subgraph.vega.api.scanner.model.IScanHost;
import com.subgraph.vega.api.scanner.modules.IPerDirectoryScannerModule;
import com.subgraph.vega.api.scanner.modules.IPerHostScannerModule;

public class ScannerTask implements Runnable, ICrawlerEventHandler {

	private final Logger logger = Logger.getLogger("scanner");
	private final Scanner scanner;
	private final IScannerConfig scannerConfig;


	private final IHttpRequestEngine requestEngine;

	private final IHttpResponseProcessor responseProcessor;
	private volatile boolean stopRequested;
	private IWebCrawler currentCrawler;
	
	ScannerTask(Scanner scanner, IScannerConfig config,  IHttpRequestEngine requestEngine) {
		this.scanner = scanner;
		this.scannerConfig = config;

		this.requestEngine = requestEngine;

		responseProcessor = new ScannerResponseProcessor(scanner.getModuleRegistry().getResponseProcessingModules(), scanner.getScanModel());
		this.requestEngine.registerResponseProcessor(responseProcessor);
		
	}
	
	void stop() {
		stopRequested = true;
		if(currentCrawler != null)
			try {
				currentCrawler.stop();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	}
	
	@Override
	public void run() {
		scanner.getScanModel().addDiscoveredURI(scannerConfig.getBaseURI());
		scanner.setScannerStatus(ScannerStatus.SCAN_CRAWLING);
		runCrawlerPhase();
		scanner.setScannerStatus(ScannerStatus.SCAN_AUDITING);
		if(!stopRequested)
			runPerHostModulePhase();
		if(!stopRequested)
			runPerDirectoryModulePhase();
		scanner.setScannerStatus(ScannerStatus.SCAN_COMPLETED);
		logger.info("Scanner completed");
	}
	
	private void runCrawlerPhase() {
		logger.info("Starting crawling phase");
		ICrawlerConfig config = scanner.getCrawlerFactory().createBasicConfig(scannerConfig.getBaseURI());
		config.addEventHandler(this);
		currentCrawler = scanner.getCrawlerFactory().create(config, requestEngine);
		currentCrawler.start();
		try {
			currentCrawler.waitFinished();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		currentCrawler = null;
		logger.info("Crawler finished");
	}
	
	@Override
	public void linkDiscovered(URI link) {
		scanner.getScanModel().addDiscoveredURI(link);		
	}

	private void runPerHostModulePhase() {
		logger.info("Starting per host module phase");
		for(IScanHost host: scanner.getScanModel().getUnscannedHosts()) {
			for(IPerHostScannerModule m: scanner.getModuleRegistry().getPerHostModules()) {
				if(stopRequested)
					return;
				m.runScan(host, requestEngine, scanner.getScanModel());
			}
		}
	}
	
	private void runPerDirectoryModulePhase() {
		logger.info("Starting per directory module phase");
		for(IScanDirectory dir: scanner.getScanModel().getUnscannedDirectories()) {
			for(IPerDirectoryScannerModule m: scanner.getModuleRegistry().getPerDirectoryModules()) {
				if(stopRequested)
					return;
				m.runScan(dir, requestEngine, scanner.getScanModel());
			}
		}
	}
}