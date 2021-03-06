/*
 * Created by Andrey Markelov 11-01-2013.
 * Copyright Mail.Ru Group 2013. All rights reserved.
 */
package ru.andreymarkelov.atlas.plugins;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.JiraDataType;
import com.atlassian.jira.JiraDataTypes;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.exception.DataAccessException;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.jql.operand.QueryLiteral;
import com.atlassian.jira.jql.query.QueryCreationContext;
import com.atlassian.jira.ofbiz.DefaultOfBizConnectionFactory;
import com.atlassian.jira.plugin.jql.function.AbstractJqlFunction;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.user.util.UserUtil;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.jira.util.MessageSetImpl;
import com.atlassian.jira.util.NotNull;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.operand.FunctionOperand;

/**
 * This JQL function finds all issues that was commented by logged user in the last time.
 * 
 * @author Andrey Markelov
 */
public class TransitionReporterFunction
    extends AbstractJqlFunction
{
    /**
     * Logger.
     */
    private static Log log = LogFactory.getLog(TransitionReporterFunction.class);

    /**
     * Processed SQL.
     */
    private final static String SQL = "SELECT CG.ISSUEID FROM changeitem CI INNER JOIN changegroup CG ON CI.GROUPID = CG.ID WHERE CI.FIELDTYPE = 'jira' AND CI.FIELD = 'status' AND CG.AUTHOR = ? AND CI.NEWSTRING = ? GROUP BY CG.ISSUEID";

    /**
     * Permission manager.
     */
    private final PermissionManager permissionManager;

    /**
     * User utils.
     */
    private final UserUtil userUtil;

    /**
     * Constructor.
     */
    public TransitionReporterFunction(
        UserUtil userUtil,
        PermissionManager permissionManager)
    {
        this.userUtil = userUtil;
        this.permissionManager = permissionManager;
    }

    @Override
    @NotNull
    public JiraDataType getDataType()
    {
        return JiraDataTypes.ISSUE;
    }

    @Override
    public int getMinimumNumberOfExpectedArguments()
    {
        return 2;
    }

    @Override
    @NotNull
    public List<QueryLiteral> getValues(
        @NotNull QueryCreationContext context,
        @NotNull FunctionOperand operand,
        @NotNull TerminalClause terminalClause)
    {
        List<String> keys = operand.getArgs();
        String user = keys.get(0);
        String status = keys.get(1);

        User userObj = userUtil.getUserObject(user);
        if (userObj == null)
        {
            return null;
        }

        List<QueryLiteral> literals = new LinkedList<QueryLiteral>();

        Connection conn = null;
        PreparedStatement pStmt = null;
        ResultSet rs = null;
        try
        {
            conn = new DefaultOfBizConnectionFactory().getConnection();
            pStmt = conn.prepareStatement(SQL);
            pStmt.setString(1, userObj.getName());
            pStmt.setString(2, status);
            rs = pStmt.executeQuery();
            IssueManager imgr = ComponentManager.getInstance().getIssueManager();
            while (rs.next())
            {
                Long l = rs.getLong(1);
                Issue issue = imgr.getIssueObject(l);
                if (issue != null && permissionManager.hasPermission(Permissions.BROWSE, issue, context.getUser()))
                {
                    literals.add(new QueryLiteral(operand, l));
                }
            }
        }
        catch (DataAccessException e)
        {
            log.error("TransitionReporterFunction::getValues - An error occured", e);
            return null;
        }
        catch (SQLException e)
        {
            log.error("TransitionReporterFunction::getValues - An error occured", e);
            return null;
        }
        finally
        {
            Utils.closeResultSet(rs);
            Utils.closeStaement(pStmt);
            Utils.closeConnection(conn);
        }

        return literals;
    }

    @Override
    @NotNull
    public MessageSet validate(
        User searcher,
        @NotNull FunctionOperand operand,
        @NotNull TerminalClause terminalClause)
    {
        MessageSet messages = new MessageSetImpl();

        List<String> keys = operand.getArgs();
        if (keys.size() != 2)
        {
            messages.addErrorMessage(ComponentAccessor.getJiraAuthenticationContext().getI18nHelper().getText("utils.incorrectparameters", operand.getName()));
        }
        else
        {
            String user = keys.get(0);
            String status = keys.get(1);

            User userObj = userUtil.getUserObject(user);
            if (userObj == null)
            {
                messages.addErrorMessage(ComponentAccessor.getJiraAuthenticationContext().getI18nHelper().getText("utils.incorrectuserparameter", operand.getName()));
            }
            else
            {
                Collection<Status> statuses = ComponentManager.getInstance().getConstantsManager().getStatusObjects();
                boolean correctStatus = false;
                for (Status statusObj : statuses)
                {
                    if (statusObj.getName().equals(status))
                    {
                        correctStatus = true;
                        break;
                    }
                }

                if (!correctStatus)
                {
                    messages.addErrorMessage(ComponentAccessor.getJiraAuthenticationContext().getI18nHelper().getText("utils.incorrectstatusparameter", status, operand.getName()));
                }
            }
        }

        return messages;
    }
}
