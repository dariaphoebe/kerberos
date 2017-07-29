<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<%@ taglib prefix = "c" uri = "http://java.sun.com/jsp/jstl/core" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="Activator.User" %>
<%@ page import="common.utils" %>
<%@ page import="java.net.URLEncoder" %>
<head><link href="../usertool.css" rel="stylesheet" type="text/css">
</head>
<div id="masthead"></div>
<div id="main">
<a href="..">Account Management</a> | <a href="activate.jsp"> Activate accounts </a>
<p>
<%!
   public String filtername(String s) {
       if (s == null)
	   return null;
       String ret = s.replaceAll("[^-_.a-z0-9]","");
       if (ret.equals(""))
	   return null;
       return ret;
   }

%>

<%

   utils.checkCsrf(request);

   String cluster = filtername(request.getParameter("cluster"));

   String username = request.getRemoteUser();
   if (username.equals("hedrick"))
      username = "dsmith";

   boolean ok = User.doUser(username, null, null, null, cluster, false, false, true);
   if (ok && utils.needsPassword(username))
       response.sendRedirect("../changepass/changepass.jsp?cluster=" + URLEncoder.encode(cluster));

   pageContext.setAttribute("ok", ok);
   pageContext.setAttribute("helpmail", Activator.Config.getConfig().helpmail);

%>

<c:if test="${ok}">

<p> You have been properly activated on cluster <%= cluster %>. 

<p> NEXT STEP:

<ul>
<li> <a href="../changepass/changepass.jsp"> Set your Computer Science password here,</a>
if you are new to Computer Science systems, or you have forgotten your password.
Computer Science Department systems have a password that is separate from
your normal University password. 

<p>
<li> If you know your Computer Science password, you are finished,
or you can <a href="activate.jsp"> Activate an account on another cluster.</a>
</ul>
</c:if> <%-- end of if ok --%>

<c:if test="${!ok}">
<p> Account activation failed. Please contact 
<a href="mailto:${helpmail}"><c:out value="${helpmail}"/></a> for help.
</c:if>
