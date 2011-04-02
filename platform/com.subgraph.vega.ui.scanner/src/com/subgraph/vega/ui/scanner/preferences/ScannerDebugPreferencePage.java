package com.subgraph.vega.ui.scanner.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.subgraph.vega.ui.scanner.Activator;

public class ScannerDebugPreferencePage extends FieldEditorPreferencePage implements
	IWorkbenchPreferencePage {

	public ScannerDebugPreferencePage() {
		super(GRID);
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
		setDescription("Scanner debugging options");		
	}

	@Override
	protected void createFieldEditors() {
		BooleanFieldEditor logRequestsField = new BooleanFieldEditor("LogAllRequests", "Log All Scanner Requests", getFieldEditorParent());
		addField(logRequestsField);		
	}

}
