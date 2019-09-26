package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.datasetutility.TwoRavensHelper;
import edu.harvard.iq.dataverse.datasetutility.WorldMapPermissionHelper;
import edu.harvard.iq.dataverse.engine.command.Command;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.impl.CreateGuestbookResponseCommand;
import edu.harvard.iq.dataverse.engine.command.impl.RequestAccessCommand;
import edu.harvard.iq.dataverse.util.FileUtil;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import org.primefaces.context.RequestContext;

/**
 *
 * @author skraffmi
 * Handles All File Download processes
 * including Guestbook responses
 */
@Stateless
@Named
public class FileDownloadServiceBean implements java.io.Serializable {

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;
    
    @EJB
    GuestbookResponseServiceBean guestbookResponseService;
    @EJB
    DatasetServiceBean datasetService;
    @EJB
    DatasetVersionServiceBean datasetVersionService;
    @EJB
    DataFileServiceBean datafileService;
    @EJB
    PermissionServiceBean permissionService;
    @EJB
    DataverseServiceBean dataverseService;
    @EJB
    UserNotificationServiceBean userNotificationService;
    @EJB
    MailServiceBean mailService;
    
    @Inject
    DataverseSession session;
    
    @EJB
    EjbDataverseEngine commandEngine;
    
    @Inject
    DataverseRequestServiceBean dvRequestService;
    
    @Inject TwoRavensHelper twoRavensHelper;
    @Inject WorldMapPermissionHelper worldMapPermissionHelper;

    private static final Logger logger = Logger.getLogger(FileDownloadServiceBean.class.getCanonicalName());
    
    
    public void writeGuestbookAndStartDownload(GuestbookResponse guestbookResponse){
        if (guestbookResponse != null && guestbookResponse.getDataFile() != null     ){
            writeGuestbookResponseRecord(guestbookResponse);
            callDownloadServlet(guestbookResponse.getFileFormat(), guestbookResponse.getDataFile().getId(), guestbookResponse.isWriteResponse());
        }
        
        if (guestbookResponse != null && guestbookResponse.getSelectedFileIds() != null){
            List<String> list = new ArrayList<>(Arrays.asList(guestbookResponse.getSelectedFileIds().split(",")));
            
            for (String idAsString : list) {
                DataFile df = datafileService.findCheapAndEasy(new Long(idAsString)) ;
                if (df != null) {
                    guestbookResponse.setDataFile(df);
                    writeGuestbookResponseRecord(guestbookResponse);
                }
            }
            
            callDownloadServlet(guestbookResponse.getSelectedFileIds(), true);
        }
        
        
    }
    
    public void writeGuestbookResponseRecord(GuestbookResponse guestbookResponse) {

        try {
            Command cmd = new CreateGuestbookResponseCommand(dvRequestService.getDataverseRequest(), guestbookResponse, guestbookResponse.getDataset());
            commandEngine.submit(cmd);
        } catch (CommandException e) {
            //if an error occurs here then download won't happen no need for response recs...

        }

    }

    public void callDownloadServlet(String multiFileString, Boolean gbRecordsWritten){
        String fileDownloadUrl = "/api/access/datafiles/" + multiFileString;
        if (gbRecordsWritten){
            fileDownloadUrl += "?gbrecs=true";
        }
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(fileDownloadUrl);
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url.");
        }

        //return fileDownloadUrl;
    }

    public void callDownloadServlet(String downloadType, Long fileId, boolean gbRecordsWritten) {
        String fileDownloadUrl = FileUtil.getFileDownloadUrlPath(downloadType, fileId, gbRecordsWritten);
        logger.fine("Redirecting to file download url: " + fileDownloadUrl);
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(fileDownloadUrl);
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url (" + fileDownloadUrl + "): " + ex);
        }
    }
    
        //public String startFileDownload(FileMetadata fileMetadata, String format) {
    public void startFileDownload(GuestbookResponse guestbookResponse, FileMetadata fileMetadata, String format) {
        boolean recordsWritten = false;
        if(!fileMetadata.getDatasetVersion().isDraft()){
           guestbookResponse = guestbookResponseService.modifyDatafileAndFormat(guestbookResponse, fileMetadata, format);
           writeGuestbookResponseRecord(guestbookResponse);
            recordsWritten = true;
        }
        callDownloadServlet(format, fileMetadata.getDataFile().getId(), recordsWritten);
        logger.fine("issued file download redirect for filemetadata "+fileMetadata.getId()+", datafile "+fileMetadata.getDataFile().getId());
    }
    
    public String startExploreDownloadLink(GuestbookResponse guestbookResponse, FileMetadata fmd){

        if (guestbookResponse != null && guestbookResponse.isWriteResponse() 
                && (( fmd != null && fmd.getDataFile() != null) || guestbookResponse.getDataFile() != null)){
            if(guestbookResponse.getDataFile() == null  && fmd != null){                
                guestbookResponse.setDataFile(fmd.getDataFile());
            }
            if (fmd == null || !fmd.getDatasetVersion().isDraft()){
                writeGuestbookResponseRecord(guestbookResponse);
            }
        }
        
        Long datafileId;
        
        if (fmd == null && guestbookResponse != null && guestbookResponse.getDataFile() != null){
            datafileId = guestbookResponse.getDataFile().getId();
        } else {
            datafileId = fmd.getDataFile().getId();
        }
        String retVal = twoRavensHelper.getDataExploreURLComplete(datafileId);
        
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(retVal);
            return retVal;
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url.");
        }
        return retVal;
    }
    
    public String startWorldMapDownloadLink(GuestbookResponse guestbookResponse, FileMetadata fmd){
                
        if (guestbookResponse != null  && guestbookResponse.isWriteResponse() && ((fmd != null && fmd.getDataFile() != null) || guestbookResponse.getDataFile() != null)){
            if(guestbookResponse.getDataFile() == null && fmd != null){
                guestbookResponse.setDataFile(fmd.getDataFile());
            }
            if (fmd == null || !fmd.getDatasetVersion().isDraft()){
                writeGuestbookResponseRecord(guestbookResponse);
            }
        }
        DataFile file = null;
        if (fmd != null){
            file  = fmd.getDataFile();
        }
        if (guestbookResponse != null && guestbookResponse.getDataFile() != null){
            file  = guestbookResponse.getDataFile();
        }
        

        String retVal = worldMapPermissionHelper.getMapLayerMetadata(file).getLayerLink();
        
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect(retVal);
            return retVal;
        } catch (IOException ex) {
            logger.info("Failed to issue a redirect to file download url.");
        }
        return retVal;
    }

    public Boolean canSeeTwoRavensExploreButton(){
        return false;
    }
    
    
    public Boolean canUserSeeExploreWorldMapButton(){
        return false;
    }
    
    public void downloadDatasetCitationXML(Dataset dataset) {
        downloadCitationXML(null, dataset);
    }

    public void downloadDatafileCitationXML(FileMetadata fileMetadata) {
        downloadCitationXML(fileMetadata, null);
    }

    public void downloadCitationXML(FileMetadata fileMetadata, Dataset dataset) {
        DatasetVersion workingVersion;
        if (dataset != null){
            workingVersion = dataset.getLatestVersion();
        } else {
            workingVersion = fileMetadata.getDatasetVersion();
        }
        String xml = datasetService.createCitationXML(workingVersion, fileMetadata);
        FacesContext ctx = FacesContext.getCurrentInstance();
        HttpServletResponse response = (HttpServletResponse) ctx.getExternalContext().getResponse();
        response.setContentType("text/xml");
        String fileNameString = "";
        if (fileMetadata == null || fileMetadata.getLabel() == null) {
            // Dataset-level citation: 
            fileNameString = "attachment;filename=" + getFileNameDOI(workingVersion) + ".xml";
        } else {
            // Datafile-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(workingVersion) + "-" + FileUtil.getCiteDataFileFilename(fileMetadata, FileUtil.FileCitationExtension.ENDNOTE);
        }
        response.setHeader("Content-Disposition", fileNameString);
        try {
            ServletOutputStream out = response.getOutputStream();
            out.write(xml.getBytes());
            out.flush();
            ctx.responseComplete();
        } catch (Exception e) {

        }
    }
    
    public void downloadDatasetCitationRIS(Dataset dataset) {

        downloadCitationRIS(null, dataset);

    }

    public void downloadDatafileCitationRIS(FileMetadata fileMetadata) {
        downloadCitationRIS(fileMetadata, null);
    }

    public void downloadCitationRIS(FileMetadata fileMetadata, Dataset dataset) {
        DatasetVersion workingVersion;
        if (dataset != null){
            workingVersion = dataset.getLatestVersion();
        } else {
            workingVersion = fileMetadata.getDatasetVersion();
        }
        String risFormatDowload = datasetService.createCitationRIS(workingVersion, fileMetadata);
        FacesContext ctx = FacesContext.getCurrentInstance();
        HttpServletResponse response = (HttpServletResponse) ctx.getExternalContext().getResponse();
        response.setContentType("application/download");

        String fileNameString = "";
        if (fileMetadata == null || fileMetadata.getLabel() == null) {
            // Dataset-level citation: 
            fileNameString = "attachment;filename=" + getFileNameDOI(workingVersion) + ".ris";
        } else {
            // Datafile-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(workingVersion) + "-" + FileUtil.getCiteDataFileFilename(fileMetadata, FileUtil.FileCitationExtension.RIS);
        }
        response.setHeader("Content-Disposition", fileNameString);

        try {
            ServletOutputStream out = response.getOutputStream();
            out.write(risFormatDowload.getBytes());
            out.flush();
            ctx.responseComplete();
        } catch (Exception e) {

        }
    }
    
    private String getFileNameDOI(DatasetVersion workingVersion) {
        Dataset ds = workingVersion.getDataset();
        return "DOI:" + ds.getAuthority() + "_" + ds.getIdentifier().toString();
    }

    public void downloadDatasetCitationBibtex(Dataset dataset) {

        downloadCitationBibtex(null, dataset);

    }

    public void downloadDatafileCitationBibtex(FileMetadata fileMetadata) {
        downloadCitationBibtex(fileMetadata, null);
    }

    public void downloadCitationBibtex(FileMetadata fileMetadata, Dataset dataset) {
        DatasetVersion workingVersion;
        if (dataset != null){
            workingVersion = dataset.getLatestVersion();
        } else {
            workingVersion = fileMetadata.getDatasetVersion();
        }
        String bibFormatDowload = new BibtexCitation(workingVersion).toString();
        FacesContext ctx = FacesContext.getCurrentInstance();
        HttpServletResponse response = (HttpServletResponse) ctx.getExternalContext().getResponse();
        response.setContentType("application/download");

        String fileNameString = "";
        if (fileMetadata == null || fileMetadata.getLabel() == null) {
            // Dataset-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(workingVersion) + ".bib";
        } else {
            // Datafile-level citation:
            fileNameString = "attachment;filename=" + getFileNameDOI(workingVersion) + "-" + FileUtil.getCiteDataFileFilename(fileMetadata, FileUtil.FileCitationExtension.BIBTEX);
        }
        response.setHeader("Content-Disposition", fileNameString);

        try {
            ServletOutputStream out = response.getOutputStream();
            out.write(bibFormatDowload.getBytes());
            out.flush();
            ctx.responseComplete();
        } catch (Exception e) {

        }
    }

    
       
    public boolean requestAccess(Long fileId) {     
        DataFile file = datafileService.find(fileId);
        if (!file.getFileAccessRequesters().contains((AuthenticatedUser) session.getUser())) {            
            try {
                commandEngine.submit(new RequestAccessCommand(dvRequestService.getDataverseRequest(), file));                        
                return true;
            } catch (CommandException ex) {
                logger.info("Unable to request access for file id " + fileId + ". Exception: " + ex);
            }             
        }
        
        return false;
    }    
    
    public void sendRequestFileAccessNotification(Dataset dataset, Long fileId) {
        Timestamp ts = new Timestamp(new Date().getTime());
        
        sendRequestFileAccessNotification(dataset,fileId,null); //send with null guestbook
    }
    
    public void sendRequestFileAccessNotification(Dataset dataset, Long fileId, GuestbookResponse gb){
        Timestamp ts = new Timestamp(new Date().getTime()); 
        UserNotification un = null;
        
        String appendMsgText = (gb == null)?("") : this.getGuestbookAppendEmailDetails(gb);
            
        List<AuthenticatedUser> mngDsPermUsers = permissionService.getUsersWithPermissionOn(Permission.ManageDatasetPermissions, dataset);
        
        for (AuthenticatedUser au : mngDsPermUsers){
            un = userNotificationService.sendUserNotification(au, ts, UserNotification.Type.REQUESTFILEACCESS, fileId);
            
            if(un != null){
                
               boolean mailed = mailService.sendNotificationEmail(un, appendMsgText, (AuthenticatedUser)session.getUser());
               if(mailed){
                   un.setEmailed(true);
                   userNotificationService.save(un);
               }    
            }
        }

        userNotificationService.sendNotification((AuthenticatedUser) session.getUser(), ts, UserNotification.Type.REQUESTEDFILEACCESS, fileId);
    }    
        
    /**
     * A helper function to create the text for the GuestbookResponse.
     * This text will be appended to the email that is sent to the users.
     * @param gb The GuestbookResponse whose details will be extracted and formatted into text to append.
     * @return String The text that will be appended to the email
     */
    private String getGuestbookAppendEmailDetails(GuestbookResponse gb){
        
        String demarcation = "*******************************************";
        String startDemarcation = "\n\n\n".concat(demarcation).concat("\n\n");
        
        String separator = ": ";
        String noResponse = "-----";
        String gbDetails = startDemarcation;
        
        java.util.ResourceBundle propsBundle = java.util.ResourceBundle.getBundle("Bundle");
        gbDetails = gbDetails.concat(propsBundle.getString("dataverse.permissionsFiles.assignDialog.accessRequestDetails").toUpperCase()); //want same heading in email
        gbDetails = gbDetails.concat(":\n\n"); 
        gbDetails = gbDetails.concat("guestbookresponse_id: ").concat(Long.toString(gb.getId())); //no point in putting in Bundle.properties I don't think
        gbDetails = gbDetails.concat("\n");
        gbDetails = gbDetails.concat("authenticateduser_id: ").concat(Long.toString(gb.getAuthenticatedUser().getId())); //no point in putting in Bundle.properties I don't think
        String userEmailConfirmed = "no";
        Timestamp userEmailConfirmedTS = gb.getAuthenticatedUser().getEmailConfirmed();
        if(userEmailConfirmedTS != null){
            userEmailConfirmed = "yes";
        }
        gbDetails = gbDetails.concat("user verified their email address: ").concat(userEmailConfirmed);
        gbDetails = gbDetails.concat(":\n\n");
        gbDetails = gbDetails.concat(propsBundle.getString("name")).concat(separator).concat(gb.getName().trim());
        gbDetails = gbDetails.concat("\n");
        gbDetails = gbDetails.concat(propsBundle.getString("email")).concat(separator).concat(gb.getEmail().trim());
        gbDetails = gbDetails.concat("\n");
        
        String resp = gb.getInstitution();
        resp = (resp == null || resp.trim().length() == 0) ? noResponse : resp.trim();
       
        gbDetails = gbDetails.concat(propsBundle.getString("institution")).concat(separator).concat(resp);
        gbDetails = gbDetails.concat("\n");
        
        resp = gb.getPosition();
        resp = (resp == null || resp.trim().length() == 0) ? noResponse : resp.trim();
        gbDetails = gbDetails.concat(propsBundle.getString("position")).concat(separator).concat(resp);
        gbDetails = gbDetails.concat("\n");
        
        List<CustomQuestionResponse> cqrsList = gb.getCustomQuestionResponses();
        
        if(cqrsList != null && !cqrsList.isEmpty()){
            gbDetails = gbDetails.concat("\n\n").concat(propsBundle.getString("dataset.manageGuestbooks.guestbook.customQuestions")).concat(":\n\n");
            
            String questionText = null;
            String questionAnswer = null;
            
            for(CustomQuestionResponse cqr: cqrsList){
                questionText = cqr.getCustomQuestion().getQuestionString();
                questionText = (questionText == null || questionText.trim().length() == 0) ? noResponse : questionText.trim();
                
                questionAnswer = cqr.getResponse();
                questionAnswer = (questionAnswer == null || questionAnswer.trim().length() == 0) ? noResponse : questionAnswer.trim();
                
                gbDetails = gbDetails.concat(questionText).concat(separator).concat(questionAnswer).concat("\n\n");
                
                questionText = null;
                questionAnswer = null;
            }
          
        }
        
        gbDetails = gbDetails.concat(demarcation);
        return gbDetails;
    }
    
    
}
