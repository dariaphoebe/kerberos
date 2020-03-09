/*
 * Copyright 2017 by Rutgers, the State University of New Jersey
 * All Rights Reserved.
 *
 * Permission to use, copy, modify, and
 * distribute this software and its documentation for any purpose and
 * without fee is hereby granted, provided that the above copyright
 * notice appear in all copies and that both that copyright notice and
 * this permission notice appear in supporting documentation, and that
 * the name of Rutgers not be used in advertising or publicity pertaining
 * to distribution of the software without specific, written prior
 * permission.  Furthermore if you modify this software you must label
 * your software as modified software and not distribute it in such a
 * fashion that it might be confused with the original Rutgers software.
 * Rutgers makes no representations about the suitability of
 * this software for any purpose.  It is provided "as is" without express
 * or implied warranty.
 */

package application;

import java.util.List;
import java.util.Date;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.security.auth.*;
import javax.security.auth.callback.*;
import javax.security.auth.login.*;
import java.io.*;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import com.sun.security.auth.callback.TextCallbackHandler;
import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.ldap.*;
import java.util.Hashtable;
import common.JndiAction;
import common.utils;
import Activator.Config;
import Activator.Match;
import application.DhcpHostsController;

@Controller
public class LoginController {

    @Autowired
    private DhcpHostsController hostsController;

    // call skinit command in a fork to take the user's password and
    // generate a Kerberos credential file, /tmp/krb5cc_USER_PID.
    // This is the actual login. 

    String makeCC (String user, String pass, List<String> messages) {
     
	int retval = -1;
	// create temporary cc and rename it for two reasons:
	//   want to make sure we can tell if login worked. skinit may return ok even if it fails.
	//      but if it fails it won't create the temporary cache.
	//   want to avoid race condition if there's a second process using it. atomic rename is
	//      safer than overwriting
	String tempcc = "/tmp/krb5cc_" + user + "_" + java.lang.Thread.currentThread().getId();
	String cc = "/tmp/krb5cc_" + user;
	// will rename if it succeeds
	
	String [] cmd = {"/usr/local/bin/skinit", "-l", "1d", "-c", tempcc, user};
	
	Process p = null;
	try {
	    p = Runtime.getRuntime().exec(cmd);
	} catch (Exception e) {
	    messages.add("unable to run skinit: " + e);
	}
	
	try (
	     PrintWriter writer = new PrintWriter(p.getOutputStream());
	     ) {
		writer.println(pass);
		writer.close();
		retval = p.waitFor();
		
		// we're not giving any error messages
		if (retval != 0)
		    messages.add("Bad username or password");
		
	    }
	catch(InterruptedException e2) {
	    messages.add("Password check process interrupted");
	}
	finally {
	    p.destroy();
	}
	
	// if it worked, rename cc to its real name
	// otherwise return fail.
	if (retval == 0) {
	    try {
		new File(tempcc).renameTo(new File(cc));
		return cc;
	    } catch (Exception e) {
		return null;
	    }
	} else {
	    try {
		new File(tempcc).delete();
	    } catch (Exception e) {
	    }
	    return null;
	}

   }

   // protect against unreasonable usernames

   public String filteruser(String s) {
       if (s == null)
	   return null;
       String ret = s.replaceAll("[^-_.a-z0-9]","");
       if (ret.equals(""))
	   return null;
       return ret;
   }
   public String filterpass(String s) {
       if (s == null)
	   return null;
       String ret = s.replaceAll("[\r\n]","");
       if (ret.equals(""))
	   return null;
       return ret;
   }

    // Login can get credentials in various ways: prompting for
    // a password, a key table, or existing credentials from a credential
    // cache. This configurtion object tells it to use a credential
    // cache, i.e. /tmp/krb5_USER_PID.

    class KerberosConfiguration extends Configuration { 
        private String cc;
 
        public KerberosConfiguration(String cc) { 
            this.cc = cc;
        } 
 
        @Override 
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) { 
            Map<String, String> options = new HashMap<String, String>(); 
            options.put("useTicketCache", "true"); 
            options.put("refreshKrb5Config", "true"); 
	    options.put("ticketCache", cc);
	    
            return new AppConfigurationEntry[]{ 
		new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
					  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, 
					  options),}; 
        } 
    } 

    public String loginGet(String app, HttpServletRequest request, HttpServletResponse response, Model model) {
	return loginGet(app, null, request, response, model);
    }

    @GetMapping("/groups/login")
    public String loginGet(@RequestParam(value="app", required=false) String app,
			   // have to pass this through for dhcp
			   @RequestParam(value="ifid", required=false) Integer ifid,
			   HttpServletRequest request, HttpServletResponse response, Model model) {
	model.addAttribute("app", (app == null) ? "" : app);
	try {
	    if (request.getSession().getAttribute("krb5subject") != null) {
		if ("user".equals(model.asMap().get("app")))
		    response.sendRedirect("../users/showuser");
		if ("hosts".equals(model.asMap().get("app")))
		    response.sendRedirect("../hosts/showhosts");
		if ("dhcp".equals(model.asMap().get("app"))) {
		    // if ifid is set, this is a login from the inventory app
		    // want to search for the interface with this id
		    if (ifid != null)
			return hostsController.hostsGet(ifid, request, response, model);
		    else 
			response.sendRedirect("../dhcp/showsubnets");
		} else {
		    response.sendRedirect("showgroups");
		}
	    }
	} catch (Exception e){
	}
	// need to pass this through for the inventory entrypoint
	model.addAttribute("ifid", ifid);
        return "groups/login";
    }

    @PostMapping("/groups/login")
    public String loginSubmit(@RequestParam(value="app", required=false) String app,
			      @RequestParam(value="ifid", required=false) Integer ifid,
			      @RequestParam(value="user", required=false) String user,
			      @RequestParam(value="pass", required=false) String pass,
			      HttpServletRequest request, HttpServletResponse response,
			      Model model) {

	List<String>messages = new ArrayList<String>();
	model.addAttribute("messages", messages);

	LoginContext lc = null;
	String username = filteruser(user);
	String password = filterpass(pass);

	if (!username.equals(user)) {
	    return loginGet(app, ifid, request, response, model);
	}

	// make credentials cache. This calls skinit in a process,
	// passing it the password the user supplied. Output is
	// a Kerberos credential file, /tmp/krb5cc_USER_PID.
	// This is the real login.


	String cc = makeCC (username, password, messages);
	if (cc == null) {
	    // should have gotten error message already
	    return loginGet(app, ifid, request, response, model);
	}

	// Do the Java login. Output is a Subject. With the arguments we're using
	// the login should succeed. We already did skinit and got a valid
	// Kerberos ticket, so the hard part is done. This just generates the
	// Java data structure, Subject, with the credentials from the ticket.
	// With other options, login might have to do the Kerberos login itself,
	// either from a password or a key table. We don't do that because (1)
	// Java login can't handle one-time passwords, and (2) we need a credential
	// cache for each user that's logged in to use with IPA commands. That
	// credential has to be something the IPA command can handle. The simplest
	// is a traditional key table file in /tmp. A funny Java object won't mean
	// anything to a command-line program like IPA.

	Configuration kconfig = new KerberosConfiguration(cc);
	try {
	    lc = new LoginContext("Groups", null, null, kconfig);
	    lc.login();
	} catch (LoginException le) {
	    messages.add("Cannot create LoginContext. " + le.getMessage());
	    return loginGet(app, ifid, request, response, model);
	} catch (SecurityException se) {
	    messages.add("Cannot create LoginContext. " + se.getMessage());
	    return loginGet(app, ifid, request, response, model);
	}

	Subject subj = lc.getSubject();  
	if (subj == null) {
	    messages.add("Login failed");
	    return loginGet(app, ifid, request, response, model);
	}

	// check group privs
   

	String filter = "(uid=" + username + ")";

	// get data on the user who is logging in
	common.JndiAction action = new common.JndiAction(new String[]{filter, ""});

	Subject.doAs(subj, action);
	// has to have data in our LDAP or login can't have worked
	if (action.val.size() == 0) {
	    messages.add("Login failed");
	    return loginGet(app, ifid, request, response, model);
	}

	var ourData = action.data.get(0);

	// now check privs
	Config conf = Config.getConfig();
	Set<String>privs = new HashSet<String>();

	// Can add group?
	if (Match.matchLdap(ourData, conf.groupmanagerfilter))
	    privs.add("addgroup");

	// DHCP manager?
	if (Match.matchLdap(ourData, conf.dhcpmanagerfilter))
	    privs.add("dhcpmanager");

	// now see if they are in login mangers. They can set login attribute
	if (Match.matchLdap(ourData, conf.loginmanagerfilter)) 
	    privs.add("loginmanager");

	// see if they are superusr
	if (Match.matchLdap(ourData, conf.superuserfilter)) 
	    privs.add("superuser");

	// now set up session and go to application
	request.getSession().setAttribute("privs", privs);
	request.getSession().setAttribute("krb5subject", subj);
	request.getSession().setAttribute("krb5user", username);
	try {
	    if ("user".equals(app)) {
		response.sendRedirect("../users/showuser");
	    } else if ("hosts".equals(app)) {
		response.sendRedirect("../hosts/showhosts");
	    } else if ("dhcp".equals(app)) {
		// if ifid is set, this is a login from the inventory app
		// want to search for the interface with this id
		if (ifid != null)
		    return hostsController.hostsGet(ifid, request, response, model);
		else
		    response.sendRedirect("../dhcp/showsubnets");
	    } else
		response.sendRedirect("showgroups");
	} catch (Exception e) {
	    messages.add("Unable to redirect to main application: " + e);
	    return loginGet(app, ifid, request, response, model);
	}

	// shouldn't happen
        return "groups/login";
    }

}
