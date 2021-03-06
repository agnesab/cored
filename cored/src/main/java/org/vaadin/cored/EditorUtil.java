package org.vaadin.cored;

import org.vaadin.aceeditor.collab.DocDiff;
import org.vaadin.aceeditor.collab.SuggestibleCollabAceEditor;
import org.vaadin.aceeditor.collab.gwt.shared.Doc;
import org.vaadin.aceeditor.gwt.ace.AceMode;
import org.vaadin.aceeditor.gwt.ace.AceTheme;
import org.vaadin.cored.model.ProjectFile;
import org.vaadin.diffsync.Shared;

public class EditorUtil {


	public static boolean isEditableWithEditor(ProjectFile file) {
		String filename = file.getName();
		int lastDot = filename.lastIndexOf(".");
		if (lastDot == -1) {
			return true; // ?
		}
		String end = filename.substring(lastDot + 1);
		return !end.equals("exe") && !end.equals("pdf"); // TODO: more?
	}

	public static SuggestibleCollabAceEditor createEditorFor(
			Shared<Doc, DocDiff> doc, ProjectFile file) {
		String filename = file.getName();

		SuggestibleCollabAceEditor editor = new SuggestibleCollabAceEditor(doc);

		AceMode mode = AceMode.forFile(filename);
		if (mode != null) {
			editor.setMode(mode);
		}
		
		editor.setTheme(AceTheme.xcode);

		return editor;
	}
	

}
