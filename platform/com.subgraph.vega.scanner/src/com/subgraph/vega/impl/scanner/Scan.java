/*******************************************************************************
 * Copyright (c) 2011 Subgraph.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Subgraph - initial API and implementation
 ******************************************************************************/
package com.subgraph.vega.impl.scanner;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.cookie.Cookie;

import com.subgraph.vega.api.http.requests.IHttpRequestEngine;
import com.subgraph.vega.api.http.requests.IHttpRequestEngineConfig;
import com.subgraph.vega.api.http.requests.IHttpRequestEngineFactory;
import com.subgraph.vega.api.model.IWorkspace;
import com.subgraph.vega.api.model.alerts.IScanInstance;
import com.subgraph.vega.api.model.requests.IRequestOriginScanner;
import com.subgraph.vega.api.scanner.IScan;
import com.subgraph.vega.api.scanner.IScanProbeResult;
import com.subgraph.vega.api.scanner.IScannerConfig;
import com.subgraph.vega.api.scanner.modules.IBasicModuleScript;
import com.subgraph.vega.api.scanner.modules.IResponseProcessingModule;
import com.subgraph.vega.api.scanner.modules.IScannerModule;
import com.subgraph.vega.api.scanner.modules.IScannerModuleRegistry;

public class Scan implements IScan {
	private final Scanner scanner;
	private final IScanInstance scanInstance;
	private final IWorkspace workspace; /** Workspace the scanInstance is locked to */
	private IScannerConfig config = new ScannerConfig();
	private volatile ScanProbe scanProbe;
	private IHttpRequestEngine requestEngine;
	private ScannerTask scannerTask;
	private Thread scannerThread;
	private List<IResponseProcessingModule> responseProcessingModules;
	private List<IBasicModuleScript> basicModules;

	/**
	 * @param scanner Scanner the scan will be run with.
	 * @param scanInstance Scan instance for this scan.
	 * @param workspace Workspace the IScanInstance was created in. Workspace lock count must be increased by 1 and will
	 * be decreased when this scan finishes.
	 */
	public Scan(Scanner scanner, IScanInstance scanInstance, IWorkspace workspace) {
		this.scanner = scanner;
		this.scanInstance = scanInstance;
		this.workspace = workspace;
		reloadModules();
	}

	@Override
	public IScannerConfig getConfig() {
		return config;
	}

	@Override
	public List<IScannerModule> getModuleList() {
		reloadModules();
		final List<IScannerModule> moduleList = new ArrayList<IScannerModule>();
		moduleList.addAll(responseProcessingModules);
		moduleList.addAll(basicModules);
		return moduleList;
	}

	@Override
	public IScanProbeResult probeTargetUri(URI uri) {
		if (!scanner.isLocked(this)) {
			throw new IllegalStateException("Scanner must be locked to this scan before sending probe requests");
		}

		if (scanInstance.getScanStatus() != IScanInstance.SCAN_IDLE) {
			throw new IllegalStateException("Unable to run a probe for a scan that is already running or complete");
		}

		if (scanProbe != null) {
			throw new IllegalStateException("Another probe is already in progress");
		}

		if (requestEngine == null) {
			requestEngine = createRequestEngine(config);
		}
		
		scanProbe = new ScanProbe(uri, requestEngine);
		final IScanProbeResult probeResult = scanProbe.runProbe();
		scanProbe = null;
		return probeResult;
	}

	@Override
	public void startScan() {
		if (!scanner.isLocked(this)) {
			throw new IllegalStateException("Scanner must be locked to this scan before a scan can start");
		}

		if (scanInstance.getScanStatus() != IScanInstance.SCAN_IDLE) {
			throw new IllegalStateException("Scan is already running or complete");
		}

		if (config.getBaseURI() == null) {
			throw new IllegalArgumentException("Cannot start scan because no baseURI was specified");
		}

		if (requestEngine == null) {
			requestEngine = createRequestEngine(config);
		}

		reloadModules();
		scannerTask = new ScannerTask(this);
		scannerThread = new Thread(scannerTask);
		workspace.getScanAlertRepository().setActiveScanInstance(scanInstance);
		scanInstance.updateScanStatus(IScanInstance.SCAN_STARTING);
		scannerThread.start();
	}

	@Override
	public void stopScan() {
		if(scanProbe != null) {
			scanProbe.abort();
		}

		if(scannerTask != null) {
			scannerTask.stop();
		} else {
			scanInstance.updateScanStatus(IScanInstance.SCAN_CANCELLED);
			doFinish();
		}
	}

	private IHttpRequestEngine createRequestEngine(IScannerConfig config) {
		final IHttpRequestEngineFactory requestEngineFactory = scanner.getHttpRequestEngineFactory();
		final IHttpRequestEngineConfig requestEngineConfig = requestEngineFactory.createConfig();
		if (config.getCookieList() != null) {
			CookieStore cookieStore = requestEngineConfig.getCookieStore();
			for (Cookie c: config.getCookieList()) {
				cookieStore.addCookie(c);
			}
		}		
		if (config.getMaxRequestsPerSecond() > 0) {
			requestEngineConfig.setRequestsPerMinute(config.getMaxRequestsPerSecond() * 60);
		}
		requestEngineConfig.setMaxConnections(config.getMaxConnections());
		requestEngineConfig.setMaxConnectionsPerRoute(config.getMaxConnections());
		requestEngineConfig.setMaximumResponseKilobytes(config.getMaxResponseKilobytes());

		final HttpClient client = requestEngineFactory.createUnencodingClient();
		final IRequestOriginScanner requestOrigin = workspace.getRequestLog().getRequestOriginScanner(scanInstance.getScanId());
		return requestEngineFactory.createRequestEngine(client, requestEngineConfig, requestOrigin);
	}

	private void reloadModules() {
		IScannerModuleRegistry moduleRegistry = scanner.getScannerModuleRegistry();
		if(responseProcessingModules == null || basicModules == null) {
			responseProcessingModules = moduleRegistry.getResponseProcessingModules();
			basicModules = moduleRegistry.getBasicModules();
		} else {
			responseProcessingModules = moduleRegistry.updateResponseProcessingModules(responseProcessingModules);
			basicModules = moduleRegistry.updateBasicModules(basicModules);
		}
	}

	public Scanner getScanner() {
		return scanner;
	}

	public IScanInstance getScanInstance() {
		return scanInstance;
	}

	public IWorkspace getWorkspace() {
		return workspace;
	}

	public List<IResponseProcessingModule> getResponseModules() {
		return responseProcessingModules;
	}
	
	public 	List<IBasicModuleScript> getBasicModules() {
		return basicModules;
	}

	public IHttpRequestEngine getRequestEngine() {
		return requestEngine;
	}

	public void doFinish() {
		workspace.getScanAlertRepository().setActiveScanInstance(null);
		getScanner().unlock();
		workspace.unlock();
	}
	
}