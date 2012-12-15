package org.vaadin.cored.model;


import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.vaadin.aceeditor.collab.DocDiff;
import org.vaadin.aceeditor.collab.gwt.shared.Doc;
import org.vaadin.aceeditor.gwt.shared.LockMarkerData;
import org.vaadin.aceeditor.gwt.shared.Marker;
import org.vaadin.chatbox.SharedChat;
import org.vaadin.chatbox.gwt.shared.Chat;
import org.vaadin.chatbox.gwt.shared.ChatDiff;
import org.vaadin.chatbox.gwt.shared.ChatLine;
import org.vaadin.cored.BuildComponent;
import org.vaadin.cored.EditorUtil;
import org.vaadin.cored.LoggerTask;
import org.vaadin.cored.MyFileUtils;
import org.vaadin.cored.ProjectLog;
import org.vaadin.cored.PropertiesUtil;
import org.vaadin.diffsync.DiffTask;
import org.vaadin.diffsync.Shared;
import org.vaadin.diffsync.Shared.Listener;

import com.vaadin.ui.MenuBar;
import com.vaadin.ui.Tree;
import com.vaadin.ui.Window;

//TODO: check synchronization

public abstract class Project {

	private static final Pattern VALID_PROJECT_NAME = Pattern.compile("[a-z][a-z0-9]*");
	
	private static final String PROPERTY_FILE_NAME="project.properties";
	
	private boolean logging = false;
	private final ProjectLog log;
	
	public enum ProjectType {
		vaadin, python, generic;
	}
	
	public interface DocListener {
		public void docCreated(ProjectFile file);
		public void docRemoved(ProjectFile file);
	}
	
	public interface DocValueChangeListener {
		public void docValueChanged(ProjectFile pf, Doc newValue);
	}

	abstract public void fillTree(Tree tree);	
	abstract public Window createNewFileWindow();
	
	/**
	 * Adds things like error checkers and other things specific to project type to the doc.
	 */
	protected void decorateDoc(ProjectFile file, Shared<Doc, DocDiff> sharedDoc) {
		// Default implementation does nothing.
	}
	
	/**
	 * Override in subclass if needed
	 */
	protected void projectInitialized(boolean createSkeleton) { }
	
	/**
	 * 
	 * @return file is editable with editor
	 */
	protected boolean isEditableFile(File f) {
		return true; // default implementation
	}
	
	/**
	 * Override in subclass if needed
	 */
	protected void projectDirCreated() { }
	
	/**
	 * Override in subclass if needed
	 */
	public void addMenuItem(MenuBar menuBar) { }
	
	/**
	 * Override in subclass if needed
	 */
	public BuildComponent createBuildComponent() {
		return null;
	}
	
	// XXX Not sure if it makes sense to use WeakReference to store listeners.
	// The idea is that storing a listener here wouldn't prevent the listener
	// to be garbage-collected.
	private CopyOnWriteArrayList<WeakReference<DocListener>> docListeners = new CopyOnWriteArrayList<WeakReference<DocListener>>();
	private CopyOnWriteArrayList<WeakReference<DocValueChangeListener>> docValueChangeListeners = new CopyOnWriteArrayList<WeakReference<DocValueChangeListener>>();

	private final String projName;

	private Map<ProjectFile, Shared<Doc, DocDiff>> files = new HashMap<ProjectFile, Shared<Doc, DocDiff>>();

	private SharedChat projectChat;
	private Team team;

	private File projectDir;

	private static volatile File projectsRootDir;

	private HashMap<ProjectFile, HashMap<String,SharedChat>> fileMarkerChats =
			new HashMap<ProjectFile, HashMap<String,SharedChat>>();

	private ProjectType projectType;

	private static HashMap<String, Project> allProjects = new HashMap<String, Project>();

	private static volatile File logDir;

	private LinkedList<Shared.Listener<Doc,DocDiff>> myDocValueChangeListeners = new LinkedList<Shared.Listener<Doc,DocDiff>>();
	
	protected Project(String name, ProjectType type) {
		name = name.toLowerCase();
		
		if (logDir!=null) {
			logging = true;
			log = new ProjectLog(new File(logDir, name+".log"));
		}
		else {
			logging = false;
			log = null;
		}
		
		team = new Team(this);
		
		projName = name;
		projectType = type;
		projectChat = new SharedChat();
		projectChat.addTask(new DiffTask<Chat, ChatDiff>() {
			public ChatDiff exec(Chat value, ChatDiff diff, long collaboratorId) {
				for (ChatLine li : diff.getAddedFrozen()) {
					if (li.getUserId()!=null) {
						log.logChat(li.getUserId(), li.getText());
					}
				}
				for (ChatLine li : diff.getAddedLive()) {
					log.logChat(li.getUserId(), li.getText());
				}
				return null;
			}
		});
		projectChat.applyDiff(ChatDiff.newLiveLine("Started project "+name));
		
		
	}
	
	
	/**
	 * 
	 * @return added new project
	 */
	private static boolean addProjectIfNotExist(Project p) {
		synchronized (allProjects) {
			Project existing = allProjects.get(p.getName());
			if (existing != null) {
				return false;
			}
			else {
				allProjects.put(p.getName(), p);
				return true;
			}
		}
	}

	public static void setProjectsRootDir(String dir) {
		synchronized (allProjects) {
			projectsRootDir = new File(dir);
			projectsRootDir.mkdir(); // may already exist, that's ok.
		}
	}

	public static File getProjectsRootDir() {
		synchronized (allProjects) {
			return projectsRootDir;
		}
	}
	
	public static void setLogDir(File dir) {
		logDir = dir;
	}

	public static List<String> getProjectNames() {
		synchronized (allProjects) {
			return new LinkedList<String>(allProjects.keySet());
		}
	}

	public static Project getProject(String pn) {
		synchronized (allProjects) {
			return allProjects.get(pn);
		}
	}
	
	public static Project getProjectTryDisk(String pn) {
		synchronized (allProjects) {
			Project p = getProject(pn);
			if (p==null) {
				p = createProjectFromDisk(pn);
			}
			return p;
		}
	}
	
	static private Project createProjectFromDisk(String pn) {
		synchronized (allProjects) {
			for (File f : projectsRootDir.listFiles()) {
				if (f.isDirectory() && f.getName().equals(pn)) {
					ProjectType type = getProjectType(f);
					Project p = createProjectIfNotExist(pn, type, false);
					if (p!=null) {
						p.readFromDisk();
						return p;
					}
				}
			}
		}
		return null;
	}

	public static List<String> getProjectDirNames() {
		synchronized (allProjects) {
			if (!projectsRootDir.exists()) {
				return Collections.emptyList();
			}

			LinkedList<String> li = new LinkedList<String>();
			for (File f : projectsRootDir.listFiles()) {
				if (f.isDirectory()) {
					li.add(f.getName());
				}
			}
			return li;
		}
	}
	
	public static Project createProjectIfNotExist(String name, ProjectType type, boolean createSkeleton) {
		Project p;
		if (ProjectType.python.equals(type)) {
			p = new PythonProject(name);
		}
		else if (ProjectType.vaadin.equals(type)){
			p = new VaadinProject(name);
		}
		else if (ProjectType.generic.equals(type)){
			p = new GenericProject(name);
		}
		else {
			throw new IllegalStateException("???");
		}
		boolean addedNew = addProjectIfNotExist(p);
		if (addedNew) {
			p.initializeProject(createSkeleton);
			return p;
		}
		return null;
	}

	
	private void initializeProject(boolean createSkeleton) {
		if (!readFromDisk()) {
			writePropertiesFile();
			projectInitialized(createSkeleton);
		}
		projectInitialized(false);
	}
	
	private ProjectType getProjectType() {
		return this.projectType;
	}
	
	private static ProjectType getProjectType(File file) {
		class CoredPropertiesFilter implements FilenameFilter {
		    public boolean accept(File dir, String name) {
		        return (name.equals(PROPERTY_FILE_NAME));
		    }
		}
		if (file.isDirectory()){
			FilenameFilter filter = new CoredPropertiesFilter();
			File[] fs = file.listFiles(filter);
			try {
				if (fs.length==1){
					Properties properties = PropertiesUtil.getProperties(fs[0]);
					Object type = properties.get("PROJECT_TYPE");
					ProjectType projectType = ProjectType.valueOf(type.toString());
					return projectType;
				}
			} catch (Exception e) {
			}
		}
		return null;
	}
	
	private boolean writePropertiesFile() {		
		Properties properties=new Properties();
		properties.setProperty("PROJECT_NAME", getName());
		properties.setProperty("PROJECT_TYPE", getProjectType().toString());
		File tagFile=new File(getProjectDir(),PROPERTY_FILE_NAME);
		FileOutputStream fstream = null;
		try {
			if(!tagFile.exists()){
					tagFile.createNewFile();
			}
			fstream = new FileOutputStream(tagFile.getAbsolutePath());
			properties.store(fstream, null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		finally {
			if (fstream!=null) {
				try {
					fstream.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return true;
	}
	
	
	public void addListenerWeakRef(DocListener li) {
		docListeners.add(new WeakReference<DocListener>(li));
	}

	public void removeListenerWeakRef(DocListener li) {
		for (WeakReference<DocListener> ref : docListeners) {
			if (li.equals(ref.get())) {
				docListeners.remove(ref);
				break;
			}
		}
	}
	
	public void addListenerWeakRef(DocValueChangeListener li) {
		docValueChangeListeners.add(new WeakReference<DocValueChangeListener>(li));
	}

	public void removeListenerWeakRef(DocValueChangeListener li) {
		for (WeakReference<DocValueChangeListener> ref : docValueChangeListeners) {
			if (li.equals(ref.get())) {
				docValueChangeListeners.remove(ref);
				break;
			}
		}
	}

	public String getName() {
		return projName;
	}

	synchronized public Shared<Doc, DocDiff> getDoc(ProjectFile file) {
		return files.get(file);
	}

	synchronized public List<ProjectFile> getProjectFiles() {
		return new LinkedList<ProjectFile>(files.keySet());
	}
	
	synchronized public ProjectFile getProjectFile(String filename) {
		for (ProjectFile pf : files.keySet()) {
			if (pf.getName().equals(filename)) {
				return pf;
			}
		}
		return null;
	}

	public Shared<Doc, DocDiff> createDoc(ProjectFile file, Doc doc,
			long collaboratorId) {
		Shared<Doc, DocDiff> sharedDoc;
		synchronized (this) {
			if (files.containsKey(file)) {
				sharedDoc = files.get(file);
				sharedDoc.setValue(doc, collaboratorId);
				return sharedDoc;
			}
			
			sharedDoc = addNewSharedDoc(file, doc);
			
			try {
				writeFileToDisk(file, doc);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		fireDocCreated(file);
		
		return sharedDoc;
	}
	
	private Shared<Doc, DocDiff> addNewSharedDoc(ProjectFile file, Doc doc) {
		Shared<Doc, DocDiff> sharedDoc = new Shared<Doc, DocDiff>(doc);
		decorateDoc(file, sharedDoc);
		if (logging) {
			sharedDoc.addTask(new LoggerTask(this,file));
		}
		files.put(file, sharedDoc);
		listenTo(file, sharedDoc);
		return sharedDoc;
	}

	private void listenTo(final ProjectFile file, Shared<Doc, DocDiff> sharedDoc) {
		Listener<Doc, DocDiff> li = new Listener<Doc, DocDiff>() {
			public void changed(Doc newValue, DocDiff diff, long collaboratorId) {
				fireDocValueChanged(file, newValue);
			}
		};
		myDocValueChangeListeners.add(li);
		sharedDoc.addListenerWeakRef(li);;
	}
	
	
	
	public Shared<Doc, DocDiff> createDoc(ProjectFile file, String content) {
		return createDoc(file, new Doc(content), Shared.NO_COLLABORATOR_ID);
	}

	public Shared<Doc, DocDiff> createDoc(ProjectFile file) {
		return createDoc(file, new Doc(), Shared.NO_COLLABORATOR_ID);
	}

	public void removeDoc(ProjectFile file) {
		boolean removed = false;
		synchronized (this) {
			if (files.containsKey(file)) {
				getLocationOfFile(file).delete();
				files.remove(file);
				removeMarkerChatsOf(file);
				removed = true;
			}
		}
		if (removed) {
			fireDocRemoved(file);
		}
	}

	synchronized public SharedChat getProjectChat() {
		return projectChat;
	}

	synchronized public SharedChat getMarkerChat(ProjectFile file, String markerId) {
		if (fileMarkerChats.containsKey(file)) {
			return fileMarkerChats.get(file).get(markerId);
		}
		return null;
	}

	synchronized public SharedChat getMarkerChatCreateIfNotExist(
			ProjectFile file, String markerId, List<ChatLine> initial) {
		HashMap<String, SharedChat> markerChats = fileMarkerChats.get(file);
		if (markerChats == null) {
			markerChats = new HashMap<String, SharedChat>();
			fileMarkerChats.put(file, markerChats);
		}
		SharedChat chat = markerChats.get(markerId);
		if (chat == null) {
			chat = new SharedChat(new Chat(initial));
			if (logging) {
				for (ChatLine li : initial) {
					log.logMarkerChat(markerId, li.getUserId(), li.getText());
				}
				chat.addTask(new MarkerChatLogTask(markerId, log));
			}
			fileMarkerChats.get(file).put(markerId, chat);
		}
		return chat;

	}

	synchronized public Shared<Chat, ChatDiff> removeMarkerChat(
			ProjectFile file, String markerId) {
		if (fileMarkerChats.containsKey(file)) {
			return fileMarkerChats.get(file).remove(markerId);
		}
		return null;
	}
	
	private HashMap<String, SharedChat> removeMarkerChatsOf(ProjectFile file) {
		return fileMarkerChats.remove(file);
	}

	synchronized public Team getTeam() {
		return team;
	}

	synchronized public void writeToDisk() throws IOException {
		for (Entry<ProjectFile, Shared<Doc, DocDiff>> e : files.entrySet()) {
			if (e.getValue() != null) {
				writeFileToDisk(e.getKey(), e.getValue().getValue());
			}
		}
	}

	private void writeFileToDisk(ProjectFile file, Doc doc) throws IOException {
		writeFileToDisk(file, doc, getLocationOfFile(file));
	}
	
	private void writeFileToDisk(ProjectFile file, Doc doc, File dest) throws IOException {
		if (doc == null) {
			return;
		}
		FileUtils.write(dest, doc.getText());
	}

	public void log(String text) {
		getProjectChat().applyDiff(ChatDiff.newLiveLine(new ChatLine(text)));
	}

	private boolean readFromDisk() {
		TreeSet<File> files = getFilesIn(getProjectDir());
		boolean isProjectDir = false;
		for (File f : files) {
			if (f.getName().equals(PROPERTY_FILE_NAME)) {
				isProjectDir = true;
			}
			else if (isEditableFile(f)) {
				readFileFromDisk(f);				
			}
		}
		return isProjectDir;
	}
	

	
	private TreeSet<File> getFilesIn(File dir) {
		TreeSet<File> files = new TreeSet<File>();
		for (File f : dir.listFiles()) {
			if (f.isDirectory()) {
				files.addAll(getFilesIn(f));
			}
			else {
				files.add(f);
			}
		}
		return files;
	}


	private void fireDocCreated(ProjectFile file) {
		boolean cleanup = false;
		for (WeakReference<DocListener> ref : docListeners) {
			DocListener li = ref.get();
			if (li == null) {
				cleanup = true;
			}
			else {
				li.docCreated(file);
			}
		}
		if (cleanup) {
			cleanupDocListeners();
		}
	}

	private void fireDocRemoved(ProjectFile file) {
		boolean cleanup = false;
		for (WeakReference<DocListener> ref : docListeners) {
			DocListener li = ref.get();
			if (li == null) {
				cleanup = true;
			}
			else {
				li.docRemoved(file);
			}
		}
		if (cleanup) {
			cleanupDocListeners();
		}
	}
	
	private void fireDocValueChanged(ProjectFile file, Doc newValue) {
		boolean cleanup = false;
		for (WeakReference<DocValueChangeListener> ref : docValueChangeListeners) {
			DocValueChangeListener li = ref.get();
			if (li == null) {
				cleanup = true;
			}
			else {
				li.docValueChanged(file, newValue);
			}
		}
		if (cleanup) {
			cleanupDocValueChangeListeners();
		}
	}
	
	private void cleanupDocListeners() {
		LinkedList<WeakReference<DocListener>> toBeRemoved = new LinkedList<WeakReference<DocListener>>();
		for (WeakReference<DocListener> ref : docListeners) {
			if (ref.get()==null) {
				toBeRemoved.add(ref);
			}
		}
		boolean rem = docListeners.removeAll(toBeRemoved);
		System.out.println("cleanupDocListeners "+toBeRemoved.size()+ " " + rem);
	}
	
	private void cleanupDocValueChangeListeners() {
		System.out.println("cleanupDocValueChangeListeners");
		LinkedList<WeakReference<DocValueChangeListener>> toBeRemoved = new LinkedList<WeakReference<DocValueChangeListener>>();
		for (WeakReference<DocValueChangeListener> ref : docValueChangeListeners) {
			if (ref.get()==null) {
				toBeRemoved.add(ref);
			}
		}
		boolean rem = docValueChangeListeners.removeAll(toBeRemoved);
		System.out.println("cleanupDocValueChangeListeners "+toBeRemoved.size()+ " " + rem);
	}
	
	final protected File getLocationOfFile(ProjectFile file) {
		return new File(getProjectDir(), file.getPath());
	}
	
	private boolean readFileFromDisk(File fileFullPath) {
		String rel = MyFileUtils.relativizePath(getProjectDir(), fileFullPath);
		return readFileFromDisk(new ProjectFile(rel));
	}
	
	private boolean readFileFromDisk(ProjectFile file) {
		String content;
		try {
			content = FileUtils.readFileToString(getLocationOfFile(file));
		} catch (IOException e) {
			return false;
		}

		if (files.containsKey(file)) {
			Shared<Doc, DocDiff> shared = files.get(file);
			shared.setValue(new Doc(content), Shared.NO_COLLABORATOR_ID);
		}
		else {
			if (EditorUtil.isEditableWithEditor(file)) {
				addNewSharedDoc(file, new Doc(content));
			}
		}
		
		return true;
	}

	synchronized public File getProjectDir() {
		if (projectsRootDir == null) {
			return null;
		}
		if (projectDir == null) {
			File pf = new File(projectsRootDir, projName);
			if (pf.exists()) {
				projectDir = pf;
			}
			else if (pf.mkdir()) {
				projectDirCreated();
				projectDir = pf;
			}
		}
		return projectDir;
	}

	synchronized public void zip(File destZipFile) {
		try {
			synchronized(projectDir) {
				writeToDisk();
				MyFileUtils.zipDir(projectDir, destZipFile);
			}
		} catch (IOException e) {
			System.err.println("WARNING: zipping failed " + projectDir);
		}
	}

	public void resetFromDisk(File dir) {
		if (projectDir.equals(dir)) {
			return;
		}
		synchronized (this) {
			team.removeAllUsers();
			try {
				FileUtils.deleteDirectory(projectDir);
				FileUtils.copyDirectory(dir, projectDir);
				readFromDisk();
			} catch (IOException e) {
				System.err.println("WARNING: reseting project "+getName()+" failed");
			}
		}
	}
	
	public static boolean isValidProjectName(String s) {
		return s!=null && VALID_PROJECT_NAME.matcher(s).matches();
	}
	
	public static void kickFromAllProjects(User user) {
		synchronized(allProjects) {
			for (Project p : allProjects.values()) {
				p.getTeam().removeUser(user);
				p.removeLocksOf(user);
			}
		}
	}
	
	synchronized public void removeCursorMarkersOf(User user, ProjectFile file) {
		Shared<Doc, DocDiff> doc;
		synchronized (files) {
			doc = files.get(file);
		}
		doc.applyDiff(user.getRemoveMarkersDiff());
	}
	
	synchronized public void removeCursorMarkersOf(User user) {
		DocDiff diff = user.getRemoveMarkersDiff();
		for (Shared<Doc, DocDiff> doc : files.values()) {
			doc.applyDiff(diff);
		}
	}
	
	synchronized public void removeLocksOf(User user) {
		for (Shared<Doc, DocDiff> doc : files.values()) {
			for (Entry<String, Marker> e : doc.getValue().getMarkers().entrySet()) {
				Marker m = e.getValue();
				if (m.getType()==Marker.Type.LOCK &&
						user.getUserId().equals(((LockMarkerData)m.getData()).getLockerId())) {
					doc.applyDiff(DocDiff.removeMarker(e.getKey()));
				}
				
			}
		}
	}
	
	public boolean canBeDeleted(ProjectFile file) {
		return true;
	}
	
	synchronized public ProjectLog getLog() {
		return log;
	}
	
	public void remove() {
		synchronized (allProjects) {
			allProjects.remove(this);
		}
	}
	
	public static boolean removeProject(String projectName) {
		synchronized (allProjects) {
			Project p = allProjects.get(projectName);
			if (p != null) {
				allProjects.remove(p);
			}
			if (projectsRootDir != null) {
				File pf = new File(projectsRootDir, projectName);
				try {
					FileUtils.deleteDirectory(pf);
				} catch (IOException e) {
					System.err.println("WARNING: failed to delete dir " + pf);
					return false;
				}
			}
		}
		return true;
	}

	
	
}