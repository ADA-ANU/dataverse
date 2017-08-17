/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.DataverseRole;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.RoleAssignee;
import edu.harvard.iq.dataverse.authorization.RoleAssigneeDisplayInfo;
import edu.harvard.iq.dataverse.authorization.groups.Group;
import edu.harvard.iq.dataverse.authorization.groups.GroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.explicit.ExplicitGroup;
import edu.harvard.iq.dataverse.authorization.groups.impl.explicit.ExplicitGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.PermissionException;
import edu.harvard.iq.dataverse.engine.command.impl.AssignRoleCommand;
import edu.harvard.iq.dataverse.engine.command.impl.RevokeRoleCommand;
import edu.harvard.iq.dataverse.util.JsfHelper;
import static edu.harvard.iq.dataverse.util.JsfHelper.JH;
import edu.harvard.iq.dataverse.util.SystemConfig;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.faces.application.FacesMessage;
import javax.faces.event.ActionEvent;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import org.apache.commons.lang.StringUtils;
import org.primefaces.event.SelectEvent;
import org.primefaces.event.ToggleSelectEvent;
import org.primefaces.event.UnselectEvent;

/**
 *
 * @author gdurand
 */
@ViewScoped
@Named
public class ManageFilePermissionsPage implements java.io.Serializable {

    private static final Logger logger = Logger.getLogger(ManageFilePermissionsPage.class.getCanonicalName());

    @EJB
    DatasetServiceBean datasetService;
    @EJB
    DataFileServiceBean datafileService;
    @EJB
    GuestbookResponseServiceBean guestbookResponseService;
    @EJB
    DataverseRoleServiceBean roleService;
    @EJB
    RoleAssigneeServiceBean roleAssigneeService;
    @EJB
    PermissionServiceBean permissionService;
    @EJB
    AuthenticationServiceBean authenticationService;
    @EJB
    ExplicitGroupServiceBean explicitGroupService;    
    @EJB 
    GroupServiceBean groupService;  
    @EJB
    UserNotificationServiceBean userNotificationService;    
    @EJB
    EjbDataverseEngine commandEngine;
    @EJB
    SystemConfig systemConfig;
    @Inject
    DataverseRequestServiceBean dvRequestService;
    @Inject
    PermissionsWrapper permissionsWrapper;
    
    @PersistenceContext(unitName = "VDCNet-ejbPU")
    EntityManager em;

    @Inject
    DataverseSession session;

    Dataset dataset = new Dataset();
    private final TreeMap<RoleAssignee,List<RoleAssignmentRow>> roleAssigneeMap = new TreeMap<>();
    private final TreeMap<DataFile,List<RoleAssignmentRow>> fileMap = new TreeMap<>();
    private final TreeMap<AuthenticatedUser,List<DataFile>> fileAccessRequestMap = new TreeMap<>();
    
    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }
    
    public TreeMap<RoleAssignee, List<RoleAssignmentRow>> getRoleAssigneeMap() {
        return roleAssigneeMap;
    }

    public TreeMap<DataFile, List<RoleAssignmentRow>> getFileMap() {
        return fileMap;
    }

    public TreeMap<AuthenticatedUser, List<DataFile>> getFileAccessRequestMap() {
        return fileAccessRequestMap;
    }
    
    public String init() {
        if (dataset.getId() != null) {
            dataset = datasetService.find(dataset.getId());
        }

        // check if dvObject exists and user has permission
        if (dataset == null) {
            return permissionsWrapper.notFound();
        }
        
        if (!permissionService.on(dataset).has(Permission.ManageDatasetPermissions)) {
            return permissionsWrapper.notAuthorized();
        }
        initMaps();
        return "";
    }
    
    private void initMaps() {
        // initialize files and usergroup list
        roleAssigneeMap.clear();
        fileMap.clear();
        fileAccessRequestMap.clear();        
        
        for (DataFile file : dataset.getFiles()) {
            // only include if the file is restricted (or it's draft version is restricted)
            //Added a null check in case there are files that have no metadata records SEK 
                if (file.getFileMetadata() != null && (file.isRestricted() || file.getFileMetadata().isRestricted())) {
                // we get the direct role assignments assigned to the file
                List<RoleAssignment> ras = roleService.directRoleAssignments(file);
                List<RoleAssignmentRow> raList = new ArrayList<>(ras.size());
                for (RoleAssignment ra : ras) {
                    // for files, only show role assignments which can download
                    if (ra.getRole().permissions().contains(Permission.DownloadFile)) {
                        raList.add(new RoleAssignmentRow(ra, roleAssigneeService.getRoleAssignee(ra.getAssigneeIdentifier()).getDisplayInfo()));                   
                        addFileToRoleAssignee(ra);                    
                    }
                }
                
                fileMap.put(file, raList);
                
                // populate the file access requests map
                for (AuthenticatedUser au : file.getFileAccessRequesters()) {
                        
                        List<DataFile> requestedFiles = fileAccessRequestMap.get(au);
                        if (requestedFiles == null) {
                            requestedFiles = new ArrayList<>();
                            fileAccessRequestMap.put(au, requestedFiles);
                        }
                  
                        requestedFiles.add(file);
                        
                }
            }  
        }  
    }
    
    private void addFileToRoleAssignee(RoleAssignment assignment) {
        RoleAssignee ra = roleAssigneeService.getRoleAssignee(assignment.getAssigneeIdentifier());
        List<RoleAssignmentRow> assignments = roleAssigneeMap.get(ra);
        if (assignments == null) {
            assignments = new ArrayList<>();
            roleAssigneeMap.put(ra, assignments);
        }
        
        assignments.add(new RoleAssignmentRow(assignment, ra.getDisplayInfo()));
    }

    /* 
     main page
     */
    
    public void removeRoleAssignments(List<RoleAssignmentRow> raRows) {
        for (RoleAssignmentRow raRow : raRows) {
            revokeRole(raRow.getId());
        }
        
        initMaps();
        showUserGroupMessages();
    }    


    /*
     view / remove roles dialog
     */  
    private DataFile selectedFile;
    private RoleAssignee selectedRoleAssignee;
    private List<RoleAssignmentRow> roleAssignments;
    private List<RoleAssignmentRow> selectedRoleAssignmentRows;

    public DataFile getSelectedFile() {
        return selectedFile;
    }

    public void setSelectedFile(DataFile selectedFile) {
        this.selectedFile = selectedFile;
    }

    public RoleAssignee getSelectedRoleAssignee() {
        return selectedRoleAssignee;
    }

    public void setSelectedRoleAssignee(RoleAssignee selectedRoleAssignee) {
        this.selectedRoleAssignee = selectedRoleAssignee;
    }
    
    public List<RoleAssignmentRow> getRoleAssignments() {
        return roleAssignments;
    }

    public void setRoleAssignments(List<RoleAssignmentRow> roleAssignments) {
        this.roleAssignments = roleAssignments;
    }

    public List<RoleAssignmentRow> getSelectedRoleAssignmentRows() {
        return selectedRoleAssignmentRows;
    }

    public void setSelectedRoleAssignmentRows(List<RoleAssignmentRow> selectedRoleAssignmentRows) {
        this.selectedRoleAssignmentRows = selectedRoleAssignmentRows;
    }
    
    public void initViewRemoveDialogByFile(DataFile file, List<RoleAssignmentRow> raRows) {
        setSelectedRoleAssignmentRows(new ArrayList());
        this.selectedFile = file;
        this.selectedRoleAssignee = null;
        this.roleAssignments = raRows;
        showFileMessages();
    }
    
    public void initViewRemoveDialogByRoleAssignee(RoleAssignee ra, List<RoleAssignmentRow> raRows) {
        setSelectedRoleAssignmentRows(new ArrayList());
        this.selectedFile = null;
        this.selectedRoleAssignee = ra;
        this.roleAssignments = raRows;
        showUserGroupMessages();
    }    
    
    public void removeRoleAssignments() {
        for (RoleAssignmentRow raRow : selectedRoleAssignmentRows) {
            revokeRole(raRow.getId());
        }
        
        initMaps();        
    }
    
    // internal method used by removeRoleAssignments
    private void revokeRole(Long roleAssignmentId) {
        RoleAssignment ra = em.find(RoleAssignment.class, roleAssignmentId);
        java.util.ResourceBundle propsBundle = java.util.ResourceBundle.getBundle("Bundle");
        
        try {
            commandEngine.submit(new RevokeRoleCommand(ra, dvRequestService.getDataverseRequest()));
            String successMsg = java.text.MessageFormat.format(propsBundle.getString("file.revokeRole.successMessage"),ra.getRole().getName(),roleAssigneeService.getRoleAssignee(ra.getAssigneeIdentifier()).getDisplayInfo().getTitle());
            JsfHelper.addSuccessMessage(successMsg);   
        } catch (PermissionException ex) {
            String excpSummary = propsBundle.getString("file.revokeRole.permissionsException.summary");
            String excpDetails = java.text.MessageFormat.format(propsBundle.getString("file.revokeRole.permissionsException.details"),ex.getRequiredPermissions().toString());
            JH.addMessage(FacesMessage.SEVERITY_ERROR,excpSummary,excpDetails);
        } catch (CommandException ex) {
            String failedMsg = java.text.MessageFormat.format(propsBundle.getString("file.revokeRole.failedMessage"),ra.getRole().getName(),roleAssigneeService.getRoleAssignee(ra.getAssigneeIdentifier()).getDisplayInfo().getTitle());
            JsfHelper.addErrorMessage(failedMsg);
            logger.log(Level.SEVERE, "Error removing role assignment: " + ex.getMessage(), ex);
        }
    }    
  
 
    /*
     grant access and refer dialogs
     */
    private List<RoleAssignee> selectedRoleAssignees;
    private List<DataFile> selectedFiles = new ArrayList<>();
    private AuthenticatedUser fileRequester;
            
    public List<RoleAssignee> getSelectedRoleAssignees() {
        return selectedRoleAssignees;
    }

    public void setSelectedRoleAssignees(List<RoleAssignee> selectedRoleAssignees) {
        this.selectedRoleAssignees = selectedRoleAssignees;
    }

    public List<DataFile> getSelectedFiles() {
        return selectedFiles;
    }

    public void setSelectedFiles(List<DataFile> selectedFiles) {
        this.selectedFiles = selectedFiles;
    }
    
    public AuthenticatedUser getFileRequester() {
        return fileRequester;
    }

    public void initAssignDialog(ActionEvent ae) {
        fileRequester = null;
        selectedRoleAssignees = null;
        selectedFiles.clear();
        showUserGroupMessages();
    }
    
    public void initAssignDialogByFile(DataFile file) {
        fileRequester = null;
        selectedRoleAssignees = null;
        selectedFiles.clear();
        selectedFiles.add(file);
        showFileMessages();
    }
    public void initAssignDialogForFileRequester(AuthenticatedUser au) {
        fileRequester = au;
        selectedRoleAssignees = null;
        selectedFiles.clear();
        selectedFiles.addAll(fileAccessRequestMap.get(au));    
        showUserGroupMessages();
    }     
    
    public List<RoleAssignee> completeRoleAssignee(String query) {
        return roleAssigneeService.filterRoleAssignees(query, dataset, selectedRoleAssignees); 
    }
    
    public ArrayList getFileRequestDetailsAsList(DataFile file){
        return guestbookResponseService.findLatestRequestAccessGuestbookResponsesAsList(file, fileRequester);
    }
    
    public void grantAccess(ActionEvent evt) {
        // Find the built in file downloader role (currently by alias)
        DataverseRole fileDownloaderRole = roleService.findBuiltinRoleByAlias(DataverseRole.FILE_DOWNLOADER);
        for (RoleAssignee roleAssignee : selectedRoleAssignees) {
            boolean sendNotification = false;
            for (DataFile file : selectedFiles) {
                if (assignRole(roleAssignee, file, fileDownloaderRole)) {
                    if (file.isReleased()) {
                        sendNotification = true;
                    }
                    // remove request, if it exist
                    if (file.getFileAccessRequesters().remove(roleAssignee)) {
                        datafileService.save(file);
                    }                  
                }               
            
            }

            if (sendNotification) {
                for (AuthenticatedUser au : roleAssigneeService.getExplicitUsers(roleAssignee)) {
                    userNotificationService.sendNotification(au, new Timestamp(new Date().getTime()), UserNotification.Type.GRANTFILEACCESS, dataset.getId());                
                }
             }
        }
        
        initMaps();
    }
    
    public void grantAccessToRequests(AuthenticatedUser au) {
        grantAccessToRequests(au, selectedFiles);
    }
    
    public void grantAccessToAllRequests(AuthenticatedUser au) {
        grantAccessToRequests(au, fileAccessRequestMap.get(au));
    }    

    private void grantAccessToRequests(AuthenticatedUser au, List<DataFile> files) {
        boolean actionPerformed = false;
        java.util.ResourceBundle propsBundle = java.util.ResourceBundle.getBundle("Bundle");
        
        // Find the built in file downloader role (currently by alias) 
        DataverseRole fileDownloaderRole = roleService.findBuiltinRoleByAlias(DataverseRole.FILE_DOWNLOADER);
        for (DataFile file : files) {
            if (assignRole(au, file, fileDownloaderRole)) {
                file.getFileAccessRequesters().remove(au);
                datafileService.save(file);
                actionPerformed = true;
            }
        }
        if (actionPerformed) {
            String successMsg = java.text.MessageFormat.format(propsBundle.getString("file.requestAccess.grant.successMessage"),au.getDisplayInfo().getTitle());
            JsfHelper.addSuccessMessage(successMsg);                        
            userNotificationService.sendNotification(au, new Timestamp(new Date().getTime()), UserNotification.Type.GRANTFILEACCESS, dataset.getId()); 
        }
        else {
            String failedMsg = java.text.MessageFormat.format(propsBundle.getString("file.requestAccess.grant.failedMessage"),au.getDisplayInfo().getTitle());
            JsfHelper.addErrorMessage(failedMsg);
        }
        initMaps(); 
        this.showUserGroupMessages();
    }
    
    public void rejectAccessToRequests(AuthenticatedUser au) {
        rejectAccessToRequests(au, selectedFiles);
    }
    
    public void rejectAccessToAllRequests(AuthenticatedUser au) {
        rejectAccessToRequests(au, fileAccessRequestMap.get(au));
    }    

    private void rejectAccessToRequests(AuthenticatedUser au, List<DataFile> files) {
        boolean actionPerformed = false;        
        java.util.ResourceBundle propsBundle = java.util.ResourceBundle.getBundle("Bundle");
        for (DataFile file : files) {               
            file.getFileAccessRequesters().remove(au);
            datafileService.save(file);
            actionPerformed = true;
        }

        if (actionPerformed) {
            String successMsg = java.text.MessageFormat.format(propsBundle.getString("file.requestAccess.reject.successMessage"),au.getDisplayInfo().getTitle());
            JsfHelper.addSuccessMessage(successMsg); 
            userNotificationService.sendNotification(au, new Timestamp(new Date().getTime()), UserNotification.Type.REJECTFILEACCESS, dataset.getId());
        } else {
            String failedMsg = java.text.MessageFormat.format(propsBundle.getString("file.requestAccess.reject.failedMessage"),au.getDisplayInfo().getTitle());
            JsfHelper.addErrorMessage(failedMsg); 
        }
        
        initMaps(); 
        this.showUserGroupMessages();
    }    

    private boolean assignRole(RoleAssignee ra,  DataFile file, DataverseRole r) {
        java.util.ResourceBundle propsBundle = java.util.ResourceBundle.getBundle("Bundle");
        
        try {
            String privateUrlToken = null;
            commandEngine.submit(new AssignRoleCommand(ra, r, file, dvRequestService.getDataverseRequest(), privateUrlToken));
            String successMsg = java.text.MessageFormat.format(propsBundle.getString("file.assignRole.successMessage"),r.getName(),ra.getDisplayInfo().getTitle(),file.getDisplayName());
            JsfHelper.addSuccessMessage(successMsg);
        } catch (PermissionException ex) {
            String excpSummary = propsBundle.getString("file.assignRole.permissionsException.summary");
            String excpDetails = java.text.MessageFormat.format(propsBundle.getString("file.assignRole.permissionsException.details"),ex.getRequiredPermissions().toString());
            JH.addMessage(FacesMessage.SEVERITY_ERROR,excpSummary,excpDetails);
            return false;
        } catch (CommandException ex) {
            String failedMsg = java.text.MessageFormat.format(propsBundle.getString("file.assignRole.failedMessage"),r.getName(),ra.getDisplayInfo().getTitle(),file.getDisplayName());
            JsfHelper.addErrorMessage(failedMsg);
            logger.log(Level.SEVERE, "Error assiging role: " + ex.getMessage(), ex);
            return false;
        }
        
        return true;
    }
    
    boolean renderUserGroupMessages = false;
    boolean renderFileMessages = false;
       
    public void showUserGroupMessages() {
        renderUserGroupMessages = true;
        renderFileMessages = false;
    }

    private void showFileMessages() {
        renderUserGroupMessages = false;
        renderFileMessages = true;
    }    
    
    public boolean isRenderUserGroupMessages() {
        return renderUserGroupMessages;
    }

    public void setRenderUserGroupMessages(boolean renderUserGroupMessages) {
        this.renderUserGroupMessages = renderUserGroupMessages;
    }

    public boolean isRenderFileMessages() {
        return renderFileMessages;
    }

    public void setRenderFileMessages(boolean renderFileMessages) {
        this.renderFileMessages = renderFileMessages;
    }

    /* functions to assist in creating mailto tags in the Access Requests panel*/
    
    public String getMailto(String email_address, String subject){
        return this.getMailto(email_address, subject,""); //need to ensure subject is properly escaped
    }
       
   
    public String getMailto(String email_address, String subject, String body){
 
        String result = "";
        
        try{
         
        result = String.format("mailto:%s?subject=%s&body=%s",
            email_address,    
            java.net.URLEncoder.encode(subject, "UTF-8").replace("+", "%20"), //replace the "+" chacacter with the space % encoding
            java.net.URLEncoder.encode(body, "UTF-8").replace("+", "%20"));  
        }catch(Exception exc){
            logger.info("Exception caught trying to format mailto uri: " + exc.getMessage());
        }
        
        return result;
    }
    
    
    public String getMailtoFromList(String email_address, DataFile dFile, ArrayList<ArrayList> rspDetailsList){
        String result = "";
        java.util.ResourceBundle propsBundle = java.util.ResourceBundle.getBundle("Bundle");
        
        String subject = propsBundle.getString("dataverse.permissionsFiles.files.mailto.subject");
            
        if( rspDetailsList != null && !rspDetailsList.isEmpty()){
            //format the guestbookResponse as a string for the email body
            String bodyDetails = propsBundle.getString("dataverse.permissionsFiles.files.mailto.datafile") + dFile.getDisplayName() + "\n\n";
            bodyDetails = bodyDetails + propsBundle.getString("dataverse.permissionsFiles.files.mailto.dataset") + dFile.getOwner().getDisplayName() + "   (" + systemConfig.getDataverseSiteUrl() + "/dataset.xhtml?persistentId=" + dFile.getOwner().getGlobalId() + ")\n\n\n\n";
            
            bodyDetails = bodyDetails + java.util.ResourceBundle.getBundle("Bundle").getString("dataverse.permissionsFiles.files.mailto.bodyStart");
           
            for(ArrayList rsp: rspDetailsList){
                bodyDetails = bodyDetails + rsp.get(0) + ":\n " + rsp.get(1)+ "\n\n";
            }
            
            result = this.getMailto(email_address, subject, bodyDetails);
         
        }
        else{
            result = this.getMailto(email_address, subject,"");
        }
        
        return result;
        
    }
    
    public boolean isEmailQR(ArrayList qrValue){
        boolean isEmail = false;
        
        if(qrValue != null && !qrValue.isEmpty()){
            if( ((String)qrValue.get(0)).toLowerCase().contains("email"))
            {
                isEmail = true;
            }
        }
        
        return isEmail;
    }
    
    // inner class used fordisplay of role assignments
    public static class RoleAssignmentRow {

        private final RoleAssigneeDisplayInfo assigneeDisplayInfo;
        private final RoleAssignment ra;

        public RoleAssignmentRow(RoleAssignment anRa, RoleAssigneeDisplayInfo disInf) {
            this.ra = anRa;
            this.assigneeDisplayInfo = disInf;
        }        
        

        public RoleAssigneeDisplayInfo getAssigneeDisplayInfo() {
            return assigneeDisplayInfo;
        }

        public DvObject getDefinitionPoint() {
            return ra.getDefinitionPoint();
        }

        
        public Long getId() {
            return ra.getId();
        }
    
    }
    
}
