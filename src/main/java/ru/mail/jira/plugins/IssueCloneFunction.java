/*
 * Created by Andrey Markelov 02-02-2013.
 * Copyright Mail.Ru Group 2013. All rights reserved.
 */
package ru.mail.jira.plugins;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.log4j.Logger;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.SubTaskManager;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.exception.CreateException;
import com.atlassian.jira.issue.AttachmentManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.link.IssueLink;
import com.atlassian.jira.issue.link.IssueLinkManager;
import com.atlassian.jira.issue.link.IssueLinkType;
import com.atlassian.jira.issue.link.IssueLinkTypeManager;
import com.atlassian.jira.issue.link.RemoteIssueLink;
import com.atlassian.jira.issue.link.RemoteIssueLinkBuilder;
import com.atlassian.jira.issue.link.RemoteIssueLinkManager;
import com.atlassian.jira.project.version.Version;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.util.AttachmentUtils;
import com.atlassian.jira.web.util.AttachmentException;
import com.atlassian.jira.workflow.function.issue.AbstractJiraFunctionProvider;
import com.google.common.base.Strings;
import com.opensymphony.module.propertyset.PropertySet;
import com.opensymphony.util.TextUtils;
import com.opensymphony.workflow.InvalidInputException;
import com.opensymphony.workflow.WorkflowException;

/**
 * Issue close post function.
 * 
 * @author Andrey Markelov
 */
public class IssueCloneFunction
    extends AbstractJiraFunctionProvider
{
    private final static Logger log = Logger.getLogger(IssueCloneFunction.class);

    private final ApplicationProperties applicationProperties;
    private final PermissionManager permissionManager;
    private final IssueLinkManager issueLinkManager;
    private final RemoteIssueLinkManager remoteIssueLinkManager;
    private final IssueLinkTypeManager issueLinkTypeManager;
    private final AttachmentManager attachmentManager;
    private final IssueFactory issueFactory;
    private final IssueManager issueManager;
    private IssueLinkType cloneIssueLinkType;
    private final Map<Long, Long> newIssueIdMap = new HashMap<Long, Long>();
    private String cloneIssueLinkTypeName;

    /**
     * Constructor.
     */
    public IssueCloneFunction(
        ApplicationProperties applicationProperties,
        PermissionManager permissionManager,
        IssueLinkManager issueLinkManager,
        RemoteIssueLinkManager remoteIssueLinkManager,
        IssueLinkTypeManager issueLinkTypeManager,
        SubTaskManager subTaskManager,
        AttachmentManager attachmentManager,
        IssueFactory issueFactory,
        IssueService issueService,
        IssueManager issueManager)
    {
        this.applicationProperties = applicationProperties;
        this.permissionManager = permissionManager;
        this.issueLinkManager = issueLinkManager;
        this.remoteIssueLinkManager = remoteIssueLinkManager;
        this.issueLinkTypeManager = issueLinkTypeManager;
        this.attachmentManager = attachmentManager;
        this.issueFactory = issueFactory;
        this.issueManager = issueManager;
    }

    /**
     * Clone attachments.
     */
    private void cloneIssueAttachments(
        Issue originalIssue,
        Issue clone,
        User user)
    throws CreateException
    {
        if (attachmentManager.attachmentsEnabled())
        {
            final List<Attachment> attachments = attachmentManager.getAttachments(originalIssue);
            final String remoteUserName = user == null ? null : user.getName();
            for (Attachment attachment : attachments)
            {
                File attachmentFile = AttachmentUtils.getAttachmentFile(attachment);
                if (attachmentFile.exists() && attachmentFile.canRead())
                {
                    try
                    {
                        attachmentManager.createAttachmentCopySourceFile(attachmentFile, attachment.getFilename(), attachment.getMimetype(), remoteUserName, clone, Collections.EMPTY_MAP, new Timestamp(System.currentTimeMillis()));
                    }
                    catch (AttachmentException e)
                    {
                        log.warn("Could not clone attachment with id '" + attachment.getId() + "' and file path '" + attachmentFile.getAbsolutePath() + "' for issue with id '" + clone.getId() + "' and key '" + clone.getKey() + "'.", e);
                    }
                }
                else
                {
                    log.warn("Could not clone attachment with id '" + attachment.getId() + "' and file path '" + attachmentFile.getAbsolutePath() + "' for issue with id '" + clone.getId() + "' and key '" + clone.getKey() + "', " +
                             "because the file path " + (attachmentFile.exists() ? "is not readable." : "does not exist."));
                }
            }
        }
    }

    /**
     * Clone issue links.
     */
    private void cloneIssueLinks(
        Issue originalIssue,
        Issue clone,
        Set<Long> originalIssueIdSet,
        User user)
    throws CreateException
    {
        if (issueLinkManager.isLinkingEnabled())
        {
            Collection<IssueLink> inwardLinks = issueLinkManager.getInwardLinks(originalIssue.getId());
            for (final IssueLink issueLink : inwardLinks)
            {
                if (copyLink(issueLink))
                {
                    Long sourceIssueId = issueLink.getSourceId();
                    if (originalIssueIdSet.contains(sourceIssueId))
                    {
                        sourceIssueId = newIssueIdMap.get(sourceIssueId);
                    }
                    if (sourceIssueId != null)
                    {
                        issueLinkManager.createIssueLink(sourceIssueId, clone.getId(), issueLink.getIssueLinkType().getId(), null, user);
                    }
                }
            }

            Collection<IssueLink> outwardLinks = issueLinkManager.getOutwardLinks(originalIssue.getId());
            for (final IssueLink issueLink : outwardLinks)
            {
                if (copyLink(issueLink))
                {
                    Long destinationId = issueLink.getDestinationId();
                    if (originalIssueIdSet.contains(destinationId))
                    {
                        destinationId = newIssueIdMap.get(destinationId);
                    }
                    if (destinationId != null)
                    {
                        issueLinkManager.createIssueLink(clone.getId(), destinationId, issueLink.getIssueLinkType().getId(), null, user);
                    }
                }
            }

            final List<RemoteIssueLink> originalLinks = remoteIssueLinkManager.getRemoteIssueLinksForIssue(originalIssue);
            for (final RemoteIssueLink originalLink : originalLinks)
            {
                final RemoteIssueLink link = new RemoteIssueLinkBuilder(originalLink).id(null).issueId(clone.getId()).build();
                remoteIssueLinkManager.createRemoteIssueLink(link, user);
            }
        }
    }

    /**
     * Check copy link.
     */
    private boolean copyLink(
        IssueLink issueLink)
    {
        return !issueLink.isSystemLink() &&
               (getCloneIssueLinkType() == null || !getCloneIssueLinkType().getId().equals(issueLink.getIssueLinkType().getId()));
    }

    @Override
    public void execute(
        Map transientVars,
        Map args,
        PropertySet ps)
    throws WorkflowException
    {
        String cloneCountStr = (String) args.get(Consts.ISSUE_CLONE_COUNT);
        String cloneWithAttchmentsStr = (String) args.get(Consts.ISSUE_CLONE_ATTACHMENTS);
        String cloneWithLinksStr = (String) args.get(Consts.ISSUE_CLONE_LINKS);

        if (!Utils.isValidStr(cloneCountStr) ||
            !Utils.isValidStr(cloneWithAttchmentsStr) ||
            !Utils.isValidStr(cloneWithLinksStr))
        {
            throw new InvalidInputException("System error. Invalid post funtion 'Issue Clone Post Function' settings");
        }

        int cloneCount;
        try
        {
            cloneCount = Integer.parseInt(cloneCountStr);
            if (cloneCount < 1)
            {
                throw new InvalidInputException("System error. Invalid post funtion 'Issue Clone Post Function' settings");
            }
        }
        catch (NumberFormatException nex)
        {
            throw new InvalidInputException("System error. Invalid post funtion 'Issue Clone Post Function' settings");
        }

        boolean cloneWithAttchments = Boolean.parseBoolean(cloneWithAttchmentsStr);
        boolean cloneWithLinks = Boolean.parseBoolean(cloneWithLinksStr);
        MutableIssue issue = getIssue(transientVars);
        User user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();

        List<Issue> newIssues = new ArrayList<Issue>();
        try
        {
            for (int i = 0; i < cloneCount; i++)
            {
                MutableIssue mu = issueFactory.cloneIssue(issue);
                setFields(issue, mu, user);
                final Issue newIssue = issueManager.createIssueObject(user, mu);
                newIssues.add(newIssue);
                newIssueIdMap.put(issue.getId(), newIssue.getId());

                final IssueLinkType cloneIssueLinkType = getCloneIssueLinkType();
                if (cloneIssueLinkType != null)
                {
                    issueLinkManager.createIssueLink(issue.getId(), newIssue.getId(), cloneIssueLinkType.getId(), null, user);
                }

                if (cloneWithAttchments)
                {
                    cloneIssueAttachments(issue, newIssue, user);
                }

                Set<Long> originalIssueIdSet = getOriginalIssueIdSet(issue);
                if (cloneWithLinks)
                {
                    cloneIssueLinks(issue, newIssue, originalIssueIdSet, user);
                }
            }
        }
        catch (Exception e)
        {
            throw new InvalidInputException("Cannot clone issue");
        }
    }

    /**
     * Filter archived versions.
     */
    private Collection<Version> filterArchivedVersions(
        Collection<Version> versions)
    {
        List<Version> tempVers = new ArrayList<Version>();
        for (Iterator<Version> versionsIt = versions.iterator(); versionsIt.hasNext();)
        {
            Version version = versionsIt.next();
            if(!version.isArchived())
            {
                tempVers.add(version);
            }
        }
        return tempVers;
    }

    /**
     * Get clone link type.
     */
    public IssueLinkType getCloneIssueLinkType()
    {
        if (cloneIssueLinkType == null)
        {
            final Collection<IssueLinkType> cloneIssueLinkTypes = issueLinkTypeManager.getIssueLinkTypesByName(getCloneLinkTypeName());
            if (!TextUtils.stringSet(getCloneLinkTypeName()))
            {
                cloneIssueLinkType = null;
            }
            else if (cloneIssueLinkTypes == null || cloneIssueLinkTypes.isEmpty())
            {
                log.warn("The clone link type '" + getCloneLinkTypeName() + "' does not exist. A link to the original issue will not be created.");
                cloneIssueLinkType = null;
            }
            else
            {
                for (Iterator<IssueLinkType> iterator = cloneIssueLinkTypes.iterator(); iterator.hasNext();)
                {
                    IssueLinkType issueLinkType = iterator.next();
                    if (issueLinkType.getName().equals(getCloneLinkTypeName()))
                    {
                        cloneIssueLinkType = issueLinkType;
                    }
                }
            }
        }

        return cloneIssueLinkType;
    }

    /**
     * Get clone link name.
     */
    public String getCloneLinkTypeName()
    {
        if (cloneIssueLinkTypeName == null)
        {
            cloneIssueLinkTypeName = applicationProperties.getDefaultBackedString(APKeys.JIRA_CLONE_LINKTYPE_NAME);
        }

        return cloneIssueLinkTypeName;
    }

    /**
     * Get clone prefix.
     */
    public String getClonePrefix()
    {
        String clonePrefixProperties = applicationProperties.getDefaultBackedString(APKeys.JIRA_CLONE_PREFIX);
        return clonePrefixProperties + (Strings.isNullOrEmpty(clonePrefixProperties) ? "" : " ");
    }

    /**
     * Get custom fields of issue.
     */
    public List<CustomField> getCustomFields(Issue issue)
    {
        return ComponentManager.getInstance().getCustomFieldManager().getCustomFieldObjects(issue.getProjectObject().getId(), issue.getIssueTypeObject().getId());
    }

    /**
     * Fill subtasks.
     */
    private Set<Long> getOriginalIssueIdSet(
        final Issue originalIssue)
    {
        Set<Long> originalIssues = new HashSet<Long>();
        originalIssues.add(originalIssue.getId());
        if (ComponentManager.getInstance().getSubTaskManager().isSubTasksEnabled())
        {
            for (final Issue issue : originalIssue.getSubTaskObjects())
            {
                originalIssues.add(issue.getId());
            }
        }
        return originalIssues;
    }

    /**
     * Can modify user?
     */
    public boolean isCanModifyReporter(
        Issue issue,
        User user)
    {
        return permissionManager.hasPermission(Permissions.MODIFY_REPORTER, issue, user);
    }

    /**
     * Set new issue fields.
     */
    protected void setFields(
        MutableIssue issue,
        MutableIssue newIssue,
        User user)
    {
        newIssue.setSummary(getClonePrefix() + issue.getSummary());
        newIssue.setCreated(null);
        newIssue.setUpdated(null);
        newIssue.setKey(null);
        newIssue.setVotes(null);
        newIssue.setStatusObject(null);
        newIssue.setWorkflowId(null);
        newIssue.setEstimate(issue.getOriginalEstimate());
        newIssue.setTimeSpent(null);
        newIssue.setResolutionDate(null);
        newIssue.setFixVersions(filterArchivedVersions(issue.getFixVersions()));
        newIssue.setAffectedVersions(filterArchivedVersions(issue.getAffectedVersions()));

        if (!isCanModifyReporter(issue, user))
        {
            issue.setReporter(user);
        }

        List<CustomField> customFields = getCustomFields(issue);
        for (Iterator<CustomField> iterator = customFields.iterator(); iterator.hasNext();)
        {
            CustomField customField = (CustomField) iterator.next();
            Object value = customField.getValue(issue);
            if (value != null)
            {
                newIssue.setCustomFieldValue(customField, value);
            }
        }
    }
}
