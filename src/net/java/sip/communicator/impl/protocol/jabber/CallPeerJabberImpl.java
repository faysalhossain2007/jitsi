/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import java.lang.reflect.*;
import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ContentPacketExtension.SendersEnum;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.neomedia.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.*;

/**
 * Implements a Jabber <tt>CallPeer</tt>.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class CallPeerJabberImpl
    extends AbstractCallPeerJabberGTalkImpl
        <CallJabberImpl, CallPeerMediaHandlerJabberImpl, JingleIQ>
{
    /**
     * The <tt>Logger</tt> used by the <tt>CallPeerJabberImpl</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(CallPeerJabberImpl.class);

    /**
     * If the call is cancelled before session-initiate is sent.
     */
    private boolean cancelled = false;

    /**
     * Synchronization object for candidates available.
     */
    private final Object candSyncRoot = new Object();

    /**
     * If the content-add does not contains candidates.
     */
    private boolean contentAddWithNoCands = false;

    /**
     * If we have processed the session initiate.
     */
    private boolean sessionInitiateProcessed = false;

    /**
     * Synchronization object. Synchronization object? Wow, who would have
     * thought! ;) Would be great to have a word on what we are syncing with it
     */
    private final Object sessionInitiateSyncRoot = new Object();

    /**
     * Synchronization object for SID.
     */
    private final Object sidSyncRoot = new Object();

    /**
     * Creates a new call peer with address <tt>peerAddress</tt>.
     *
     * @param peerAddress the Jabber address of the new call peer.
     * @param owningCall the call that contains this call peer.
     */
    public CallPeerJabberImpl(String         peerAddress,
                              CallJabberImpl owningCall)
    {
        super(peerAddress, owningCall);

        setMediaHandler(new CallPeerMediaHandlerJabberImpl(this));
    }

    /**
     * Creates a new call peer with address <tt>peerAddress</tt>.
     *
     * @param peerAddress the Jabber address of the new call peer.
     * @param owningCall the call that contains this call peer.
     * @param sessionIQ Session initiate IQ
     */
    public CallPeerJabberImpl(String         peerAddress,
                              CallJabberImpl owningCall,
                              JingleIQ       sessionIQ)
    {
        this(peerAddress, owningCall);
        this.sessionInitIQ = sessionIQ;
    }

    /**
     * Indicates a user request to answer an incoming call from this
     * <tt>CallPeer</tt>.
     *
     * Sends an OK response to <tt>callPeer</tt>. Make sure that the call
     * peer contains an SDP description when you call this method.
     *
     * @throws OperationFailedException if we fail to create or send the
     * response.
     */
    public synchronized void answer()
        throws OperationFailedException
    {
        Iterable<ContentPacketExtension> answer;

        try
        {
            CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();

            mediaHandler
                .getTransportManager().wrapupConnectivityEstablishment();
            answer = mediaHandler.generateSessionAccept();
        }
        catch(Exception exc)
        {
            logger.info("Failed to answer an incoming call", exc);

            //send an error response
            String reasonText = "Error: " + exc.getMessage();
            JingleIQ errResp
                = JinglePacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getSID(),
                        Reason.FAILED_APPLICATION,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        JingleIQ response
            = JinglePacketFactory.createSessionAccept(
                    sessionInitIQ.getTo(),
                    sessionInitIQ.getFrom(),
                    getSID(),
                    answer);

        //send the packet first and start the stream later  in case the media
        //relay needs to see it before letting hole punching techniques through.
        getProtocolProvider().getConnection().sendPacket(response);

        try
        {
            getMediaHandler().start();
        }
        catch(UndeclaredThrowableException e)
        {
            Throwable exc = e.getUndeclaredThrowable();

            logger.info("Failed to establish a connection", exc);

            //send an error response
            String reasonText = "Error: " + exc.getMessage();
            JingleIQ errResp
                = JinglePacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getSID(),
                        Reason.GENERAL_ERROR,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        //tell everyone we are connecting so that the audio notifications would
        //stop
        setState(CallPeerState.CONNECTED);
    }

    /**
     * Returns the session ID of the Jingle session associated with this call.
     *
     * @return the session ID of the Jingle session associated with this call.
     */
    public String getSID()
    {
        return sessionInitIQ != null ? sessionInitIQ.getSID() : null;
    }

    /**
     * Returns the IQ ID of the Jingle session-initiate packet associated with
     * this call.
     *
     * @return the IQ ID of the Jingle session-initiate packet associated with
     * this call.
     */
    public JingleIQ getSessionIQ()
    {
        return sessionInitIQ;
    }

    /**
     * Ends the call with this <tt>CallPeer</tt>. Depending on the state
     * of the peer the method would send a CANCEL, BYE, or BUSY_HERE message
     * and set the new state to DISCONNECTED.
     *
     * @param failed indicates if the hangup is following to a call failure or
     * simply a disconnect
     * @param reasonText the text, if any, to be set on the
     * <tt>ReasonPacketExtension</tt> as the value of its
     * @param reasonOtherExtension the <tt>PacketExtension</tt>, if any, to be
     * set on the <tt>ReasonPacketExtension</tt> as the value of its
     * <tt>otherExtension</tt> property
     */
    public void hangup(boolean failed,
                       String reasonText,
                       PacketExtension reasonOtherExtension)
    {
        // do nothing if the call is already ended
        if (CallPeerState.DISCONNECTED.equals(getState())
            || CallPeerState.FAILED.equals(getState()))
        {
            if (logger.isDebugEnabled())
                logger.debug("Ignoring a request to hangup a call peer "
                        + "that is already DISCONNECTED");
            return;
        }

        CallPeerState prevPeerState = getState();

        setState(
                failed ? CallPeerState.FAILED : CallPeerState.DISCONNECTED,
                reasonText);

        JingleIQ responseIQ = null;

        if (prevPeerState.equals(CallPeerState.CONNECTED)
            || CallPeerState.isOnHold(prevPeerState))
        {
            responseIQ = JinglePacketFactory.createBye(
                getProtocolProvider().getOurJID(), peerJID, getSID());
        }
        else if (CallPeerState.CONNECTING.equals(prevPeerState)
            || CallPeerState.CONNECTING_WITH_EARLY_MEDIA.equals(prevPeerState)
            || CallPeerState.ALERTING_REMOTE_SIDE.equals(prevPeerState))
        {
            String jingleSID = getSID();

            if(jingleSID == null)
            {
                synchronized(sidSyncRoot)
                {
                    // we cancelled the call too early because the jingleSID
                    // is null (i.e. the session-initiate has not been created)
                    // and no need to send the session-terminate
                    cancelled = true;
                    return;
                }
            }

            responseIQ = JinglePacketFactory.createCancel(
                getProtocolProvider().getOurJID(), peerJID, getSID());
        }
        else if (prevPeerState.equals(CallPeerState.INCOMING_CALL))
        {
            responseIQ = JinglePacketFactory.createBusy(
                getProtocolProvider().getOurJID(), peerJID, getSID());
        }
        else if (prevPeerState.equals(CallPeerState.BUSY)
                 || prevPeerState.equals(CallPeerState.FAILED))
        {
            // For FAILED and BUSY we only need to update CALL_STATUS
            // as everything else has been done already.
        }
        else
        {
            logger.info("Could not determine call peer state!");
        }

        if (responseIQ != null)
        {
            if (reasonOtherExtension != null)
            {
                ReasonPacketExtension reason
                    = (ReasonPacketExtension)
                        responseIQ.getExtension(
                                ReasonPacketExtension.ELEMENT_NAME,
                                ReasonPacketExtension.NAMESPACE);

                if (reason != null)
                {
                    reason.setOtherExtension(reasonOtherExtension);
                }
                else if(reasonOtherExtension instanceof ReasonPacketExtension)
                {
                    responseIQ.setReason(
                        (ReasonPacketExtension)reasonOtherExtension);
                }
            }

            getProtocolProvider().getConnection().sendPacket(responseIQ);
        }
    }

    /**
     * Processes the session initiation {@link JingleIQ} that we were created
     * with, passing its content to the media handler and then sends either a
     * "session-info/ringing" or a "session-terminate" response.
     *
     * @param sessionInitiateExtensions a collection of additional and optional
     * <tt>PacketExtension</tt>s to be added to the <tt>session-initiate</tt>
     * {@link JingleIQ} which is to initiate the session with this
     * <tt>CallPeerJabberImpl</tt>
     * @throws OperationFailedException exception
     */
    protected synchronized void initiateSession(
            Iterable<PacketExtension> sessionInitiateExtensions)
        throws OperationFailedException
    {
        initiator = false;

        //Create the media description that we'd like to send to the other side.
        List<ContentPacketExtension> offer
            = getMediaHandler().createContentList();

        //send a ringing response
        if (logger.isTraceEnabled())
            logger.trace("will send ringing response: ");

        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();

        synchronized(sidSyncRoot)
        {
            sessionInitIQ
                = JinglePacketFactory.createSessionInitiate(
                    protocolProvider.getOurJID(),
                    this.peerJID,
                    JingleIQ.generateSID(),
                    offer);

            if(cancelled)
            {
                // we cancelled the call too early so no need to send the
                // session-initiate to peer
                getMediaHandler().getTransportManager().close();
                return;
            }
        }

        if (sessionInitiateExtensions != null)
        {
            for (PacketExtension sessionInitiateExtension
                    : sessionInitiateExtensions)
            {
                sessionInitIQ.addExtension(sessionInitiateExtension);
            }
        }

        protocolProvider.getConnection().sendPacket(sessionInitIQ);
    }

    /**
     * Notifies this instance that a specific <tt>ColibriConferenceIQ</tt> has
     * been received. This <tt>CallPeerJabberImpl</tt> uses the part of the
     * information provided in the specified <tt>conferenceIQ</tt> which
     * concerns it only.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> which has been
     * received
     */
    void processColibriConferenceIQ(ColibriConferenceIQ conferenceIQ)
    {
        /*
         * CallPeerJabberImpl does not itself/directly know the specifics
         * related to the channels allocated on the Jitsi VideoBridge server.
         * The channels contain transport and media-related information so
         * forward the notification to CallPeerMediaHandlerJabberImpl. 
         */
        getMediaHandler().processColibriConferenceIQ(conferenceIQ);
    }

    /**
     * Processes the content-accept {@link JingleIQ}.
     *
     * @param content The {@link JingleIQ} that contains content that remote
     * peer has accepted
     */
    public void processContentAccept(JingleIQ content)
    {
        List<ContentPacketExtension> contents = content.getContentList();

        try
        {
            CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();

            mediaHandler
                .getTransportManager().wrapupConnectivityEstablishment();
            mediaHandler.processAnswer(contents);
        }
        catch (Exception e)
        {
            logger.warn("Failed to process a content-accept", e);

            // Send an error response.
            String reason = "Error: " + e.getMessage();
            JingleIQ errResp
                = JinglePacketFactory.createSessionTerminate(
                    sessionInitIQ.getTo(),
                    sessionInitIQ.getFrom(),
                    sessionInitIQ.getSID(),
                    Reason.INCOMPATIBLE_PARAMETERS,
                    reason);

            setState(CallPeerState.FAILED, reason);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        getMediaHandler().start();
    }

    /**
     * Processes the content-add {@link JingleIQ}.
     *
     * @param content The {@link JingleIQ} that contains content that remote
     * peer wants to be added
     */
    public void processContentAdd(final JingleIQ content)
    {
        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();
        List<ContentPacketExtension> contents = content.getContentList();
        Iterable<ContentPacketExtension> answerContents;
        JingleIQ contentIQ;
        boolean noCands = false;
        MediaStream oldVideoStream = mediaHandler.getStream(MediaType.VIDEO);

        if(logger.isInfoEnabled())
            logger.info("Looking for candidates in content-add.");
        try
        {
            if(!contentAddWithNoCands)
            {
                mediaHandler.processOffer(contents);

                /*
                 * Gingle transport will not put candidate in session-initiate
                 * and content-add.
                 */
                for(ContentPacketExtension c : contents)
                {
                    if(JingleUtils.getFirstCandidate(c, 1) == null)
                    {
                        contentAddWithNoCands = true;
                        noCands = true;
                    }
                }
            }

            // if no candidates are present, launch a new Thread which will
            // process and wait for the connectivity establishment (otherwise
            // the existing thread will be blocked and thus cannot receive
            // transport-info with candidates
            if(noCands)
            {
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            synchronized(candSyncRoot)
                            {
                                candSyncRoot.wait();
                            }
                        }
                        catch(InterruptedException e)
                        {
                        }

                        processContentAdd(content);
                        contentAddWithNoCands = false;
                    }
                }.start();
                if(logger.isInfoEnabled())
                    logger.info("No candidates found in content-add, started "
                                + "new thread.");
                return;
            }

            mediaHandler.getTransportManager().
                wrapupConnectivityEstablishment();
            if(logger.isInfoEnabled())
                logger.info("Wrapping up connectivity establishment");
            answerContents = mediaHandler.generateSessionAccept();
            contentIQ = null;
        }
        catch(Exception e)
        {
            logger.warn("Exception occurred", e);

            answerContents = null;
            contentIQ
                = JinglePacketFactory.createContentReject(
                        getProtocolProvider().getOurJID(),
                        this.peerJID,
                        getSID(),
                        answerContents);
        }

        if(contentIQ == null)
        {
            /* send content-accept */
            contentIQ
                = JinglePacketFactory.createContentAccept(
                        getProtocolProvider().getOurJID(),
                        this.peerJID,
                        getSID(),
                        answerContents);
            for (ContentPacketExtension c : answerContents)
                mediaHandler.setSenders(MediaType.parseString(c.getName()),
                        c.getSenders());
        }

        getProtocolProvider().getConnection().sendPacket(contentIQ);
        mediaHandler.start();

        /*
         * If a remote peer turns her video on in a conference which is hosted
         * by the local peer and the local peer is not streaming her local
         * video, reinvite the other remote peers to enable RTP translation.
         */
        if (oldVideoStream == null)
        {
            MediaStream newVideoStream
                = mediaHandler.getStream(MediaType.VIDEO);

            if ((newVideoStream != null)
                    && mediaHandler.isRTPTranslationEnabled(MediaType.VIDEO))
            {
                try
                {
                    getCall().modifyVideoContent();
                }
                catch (OperationFailedException ofe)
                {
                    logger.error("Failed to enable RTP translation", ofe);
                }
            }
        }
    }

    /**
     * Processes the content-modify {@link JingleIQ}.
     *
     * @param content The {@link JingleIQ} that contains content that remote
     * peer wants to be modified
     */
    public void processContentModify(JingleIQ content)
    {
        ContentPacketExtension ext = content.getContentList().get(0);

        try
        {
            boolean modify
                = (ext.getFirstChildOfType(RtpDescriptionPacketExtension.class)
                    != null);

            getMediaHandler().reinitContent(ext.getName(), ext, modify);
            if (MediaType.VIDEO.toString().equals(ext.getName()))
                getCall().modifyVideoContent();
        }
        catch(Exception e)
        {
            logger.info("Failed to process an incoming content-modify", e);

            // Send an error response.
            String reason = "Error: " + e.getMessage();
            JingleIQ errResp
                = JinglePacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getSID(),
                        Reason.INCOMPATIBLE_PARAMETERS,
                        reason);

            setState(CallPeerState.FAILED, reason);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }
    }

    /**
     * Processes the content-reject {@link JingleIQ}.
     *
     * @param content The {@link JingleIQ}
     */
    public void processContentReject(JingleIQ content)
    {
        if(content.getContentList().isEmpty())
        {
            //send an error response;
            JingleIQ errResp = JinglePacketFactory.createSessionTerminate(
                sessionInitIQ.getTo(), sessionInitIQ.getFrom(),
                sessionInitIQ.getSID(), Reason.INCOMPATIBLE_PARAMETERS,
                "Error: content rejected");

            setState(CallPeerState.FAILED, "Error: content rejected");
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }
    }

    /**
     * Processes the content-remove {@link JingleIQ}.
     *
     * @param content The {@link JingleIQ} that contains content that remote
     * peer wants to be removed
     */
    public void processContentRemove(JingleIQ content)
    {
        List<ContentPacketExtension> contents = content.getContentList();

        if (!contents.isEmpty())
        {
            CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();

            for(ContentPacketExtension c : contents)
                mediaHandler.removeContent(c.getName());

            /*
             * TODO XEP-0166: Jingle says: If the content-remove results in zero
             * content definitions for the session, the entity that receives the
             * content-remove SHOULD send a session-terminate action to the
             * other party (since a session with no content definitions is
             * void).
             */
        }
        try
        {
            getCall().modifyVideoContent();
        }
        catch (Exception e)
        {
            logger.warn("Failed to update Jingle sessions");
        }
    }

    /**
     * Processes the session initiation {@link JingleIQ} that we were created
     * with, passing its content to the media handler and then sends either a
     * "session-info/ringing" or a "session-terminate" response.
     *
     * @param sessionInitIQ The {@link JingleIQ} that created the session that
     * we are handling here.
     */
    public void processSessionAccept(JingleIQ sessionInitIQ)
    {
        this.sessionInitIQ = sessionInitIQ;

        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();
        List<ContentPacketExtension> answer = sessionInitIQ.getContentList();

        try
        {
            mediaHandler
                .getTransportManager().wrapupConnectivityEstablishment();
            mediaHandler.processAnswer(answer);
        }
        catch(Exception exc)
        {
            if (logger.isInfoEnabled())
                logger.info("Failed to process a session-accept", exc);

            //send an error response;
            JingleIQ errResp = JinglePacketFactory.createSessionTerminate(
                sessionInitIQ.getTo(), sessionInitIQ.getFrom(),
                sessionInitIQ.getSID(), Reason.INCOMPATIBLE_PARAMETERS,
                exc.getClass().getName() + ": " + exc.getMessage());

            setState(CallPeerState.FAILED, "Error: " + exc.getMessage());
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        //tell everyone we are connecting so that the audio notifications would
        //stop
        setState(CallPeerState.CONNECTED);

        mediaHandler.start();
    }

    /**
     * Handles the specified session <tt>info</tt> packet according to its
     * content.
     *
     * @param info the {@link SessionInfoPacketExtension} that we just received.
     */
    public void processSessionInfo(SessionInfoPacketExtension info)
    {
        switch (info.getType())
        {
        case ringing:
            setState(CallPeerState.ALERTING_REMOTE_SIDE);
            break;
        case hold:
            getMediaHandler().setRemotelyOnHold(true);
            reevalRemoteHoldStatus();
            break;
        case unhold:
        case active:
            getMediaHandler().setRemotelyOnHold(false);
            reevalRemoteHoldStatus();
            break;
        default:
            logger.warn("Received SessionInfoPacketExtension of unknown type");
        }
    }

    /**
     * Processes the session initiation {@link JingleIQ} that we were created
     * with, passing its content to the media handler and then sends either a
     * "session-info/ringing" or a "session-terminate" response.
     *
     * @param sessionInitIQ The {@link JingleIQ} that created the session that
     * we are handling here.
     */
    protected synchronized void processSessionInitiate(JingleIQ sessionInitIQ)
    {
        // Do initiate the session.
        this.sessionInitIQ = sessionInitIQ;
        this.initiator = true;

        // This is the SDP offer that came from the initial session-initiate.
        // Contrary to SIP, we are guaranteed to have content because XEP-0166
        // says: "A session consists of at least one content type at a time."
        List<ContentPacketExtension> offer = sessionInitIQ.getContentList();

        try
        {
            getMediaHandler().processOffer(offer);

            CoinPacketExtension coin = null;

            for(PacketExtension ext : sessionInitIQ.getExtensions())
            {
                if(ext.getElementName().equals(
                        CoinPacketExtension.ELEMENT_NAME))
                {
                    coin = (CoinPacketExtension)ext;
                    break;
                }
            }

            /* does the call peer acts as a conference focus ? */
            if(coin != null)
            {
                setConferenceFocus(Boolean.parseBoolean(
                        (String)coin.getAttribute("isfocus")));
            }
        }
        catch(Exception ex)
        {
            logger.info("Failed to process an incoming session initiate", ex);

            //send an error response;
            String reasonText = "Error: " + ex.getMessage();
            JingleIQ errResp
                = JinglePacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getSID(),
                        Reason.INCOMPATIBLE_PARAMETERS,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);
            return;
        }

        // If we do not get the info about the remote peer yet. Get it right
        // now.
        if(this.getDiscoveryInfo() == null)
        {
            String calleeURI = sessionInitIQ.getFrom();
            retrieveDiscoveryInfo(calleeURI);
        }

        //send a ringing response
        if (logger.isTraceEnabled())
            logger.trace("will send ringing response: ");

        getProtocolProvider().getConnection().sendPacket(
                JinglePacketFactory.createRinging(sessionInitIQ));

        synchronized(sessionInitiateSyncRoot)
        {
            sessionInitiateProcessed = true;
            sessionInitiateSyncRoot.notify();
        }

        //if this is a 3264 initiator, let's give them an early peek at our
        //answer so that they could start ICE (SIP-2-Jingle gateways won't
        //be able to send their candidates unless they have this)
        if(    getDiscoveryInfo() != null
            && getDiscoveryInfo().containsFeature(
                        ProtocolProviderServiceJabberImpl.URN_IETF_RFC_3264))
        {
            getProtocolProvider().getConnection().sendPacket(
                            JinglePacketFactory.createDescriptionInfo(
                                sessionInitIQ.getTo(),
                                sessionInitIQ.getFrom(),
                                sessionInitIQ.getSID(),
                                getMediaHandler().getLocalContentList()
                            ));
        }
    }

    /**
     * Puts this peer into a {@link CallPeerState#DISCONNECTED}, indicating a
     * reason to the user, if there is one.
     *
     * @param jingleIQ the {@link JingleIQ} that's terminating our session.
     */
    public void processSessionTerminate(JingleIQ jingleIQ)
    {
        String reasonStr = "Call ended by remote side.";
        ReasonPacketExtension reasonExt = jingleIQ.getReason();

        if(reasonExt != null)
        {
            Reason reason = reasonExt.getReason();

            if(reason != null)
                reasonStr += " Reason: " + reason.toString() + ".";

            String text = reasonExt.getText();

            if(text != null)
                reasonStr += " " + text;
        }

        setState(CallPeerState.DISCONNECTED, reasonStr);
    }

    /**
     * Processes a specific "XEP-0251: Jingle Session Transfer"
     * <tt>transfer</tt> packet (extension).
     *
     * @param transfer the "XEP-0251: Jingle Session Transfer" transfer packet
     * (extension) to process
     * @throws OperationFailedException if anything goes wrong while processing
     * the specified <tt>transfer</tt> packet (extension)
     */
    public void processTransfer(TransferPacketExtension transfer)
        throws OperationFailedException
    {
        String attendantAddress = transfer.getFrom();

        if (attendantAddress == null)
        {
            throw new OperationFailedException(
                    "Session transfer must contain a \'from\' attribute value.",
                    OperationFailedException.ILLEGAL_ARGUMENT);
        }

        String calleeAddress = transfer.getTo();

        if (calleeAddress == null)
        {
            throw new OperationFailedException(
                    "Session transfer must contain a \'to\' attribute value.",
                    OperationFailedException.ILLEGAL_ARGUMENT);
        }

        // Checks if the transfer remote peer is contained by the roster of this
        // account.
        Roster roster = getProtocolProvider().getConnection().getRoster();
        if(!roster.contains(StringUtils.parseBareAddress(calleeAddress)))
        {
            String failedMessage =
                    "Tranfer impossible:\n"
                    + "Account roster does not contain tansfer peer: "
                    + StringUtils.parseBareAddress(calleeAddress);
            setState(CallPeerState.FAILED, failedMessage);
            logger.info(failedMessage);
        }

        OperationSetBasicTelephonyJabberImpl basicTelephony
            = (OperationSetBasicTelephonyJabberImpl)
                getProtocolProvider()
                    .getOperationSet(OperationSetBasicTelephony.class);
        CallJabberImpl calleeCall = new CallJabberImpl(basicTelephony);
        TransferPacketExtension calleeTransfer = new TransferPacketExtension();
        String sid = transfer.getSID();

        calleeTransfer.setFrom(attendantAddress);
        if (sid != null)
        {
            calleeTransfer.setSID(sid);
            calleeTransfer.setTo(calleeAddress);
        }
        basicTelephony.createOutgoingCall(
                calleeCall,
                calleeAddress,
                Arrays.asList(new PacketExtension[] { calleeTransfer }));
    }

    /**
     * Processes the <tt>transport-info</tt> {@link JingleIQ}.
     *
     * @param jingleIQ the <tt>transport-info</tt> {@link JingleIQ} to process
     */
    public void processTransportInfo(JingleIQ jingleIQ)
    {
        /*
         * The transport-info action is used to exchange transport candidates so
         * it only concerns the mediaHandler.
         */
        try
        {
            if(isInitiator())
            {
                synchronized(sessionInitiateSyncRoot)
                {
                    if(!sessionInitiateProcessed)
                    {
                        try
                        {
                            sessionInitiateSyncRoot.wait();
                        }
                        catch(InterruptedException e)
                        {
                        }
                    }
                }
            }

            getMediaHandler().processTransportInfo(
                jingleIQ.getContentList());
        }
        catch (OperationFailedException ofe)
        {
            logger.warn("Failed to process an incoming transport-info", ofe);

            //send an error response
            String reasonText = "Error: " + ofe.getMessage();
            JingleIQ errResp
                = JinglePacketFactory.createSessionTerminate(
                        sessionInitIQ.getTo(),
                        sessionInitIQ.getFrom(),
                        sessionInitIQ.getSID(),
                        Reason.GENERAL_ERROR,
                        reasonText);

            setState(CallPeerState.FAILED, reasonText);
            getProtocolProvider().getConnection().sendPacket(errResp);

            return;
        }

        synchronized(candSyncRoot)
        {
            candSyncRoot.notify();
        }
    }

    /**
     * Puts the <tt>CallPeer</tt> represented by this instance on or off hold.
     *
     * @param onHold <tt>true</tt> to have the <tt>CallPeer</tt> put on hold;
     * <tt>false</tt>, otherwise
     *
     * @throws OperationFailedException if we fail to construct or send the
     * INVITE request putting the remote side on/off hold.
     */
    public void putOnHold(boolean onHold)
        throws OperationFailedException
    {
        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();

        mediaHandler.setLocallyOnHold(onHold);

        SessionInfoType type;

        if(onHold)
            type = SessionInfoType.hold;
        else
        {
            type = SessionInfoType.unhold;
            getMediaHandler().reinitAllContents();
        }

        //we are now on hold and need to realize this before potentially
        //spoiling it all with an exception while sending the packet :).
        reevalLocalHoldStatus();

        JingleIQ onHoldIQ = JinglePacketFactory.createSessionInfo(
                        getProtocolProvider().getOurJID(),
                        peerJID,
                        getSID(),
                        type);

        getProtocolProvider().getConnection().sendPacket(onHoldIQ);
    }

    /**
     * Send a <tt>content-add</tt> to add video setup.
     */
    private void sendAddVideoContent()
    {
        List<ContentPacketExtension> contents;

        try
        {
            contents = getMediaHandler().createContentList(MediaType.VIDEO);
        }
        catch(Exception exc)
        {
            logger.warn("Failed to gather content for video type", exc);
            return;
        }

        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();
        JingleIQ contentIQ
            = JinglePacketFactory.createContentAdd(
                    protocolProvider.getOurJID(),
                    this.peerJID,
                    getSID(),
                    contents);

        protocolProvider.getConnection().sendPacket(contentIQ);
    }

    /**
     * Sends a <tt>content</tt> message to reflect changes in the setup such as
     * the local peer/user becoming a conference focus.
     */
    public void sendCoinSessionInfo()
    {
        JingleIQ sessionInfoIQ
            = JinglePacketFactory.createSessionInfo(
                    getProtocolProvider().getOurJID(),
                    this.peerJID,
                    getSID());
        CoinPacketExtension coinExt
            = new CoinPacketExtension(getCall().isConferenceFocus());

        sessionInfoIQ.addExtension(coinExt);
        getProtocolProvider().getConnection().sendPacket(sessionInfoIQ);
    }

    /**
     * Returns the <tt>MediaDirection</tt> that should be used for the content
     * of type <tt>mediaType</tt> in the Jingle session for this
     * <tt>CallPeer</tt>.
     * If we are the focus of a conference and are doing RTP translation,
     * takes into account the other <tt>CallPeer</tt>s in the <tt>Call</tt>.
     *
     * @param mediaType the <tt>MediaType</tt> for which to return the
     * <tt>MediaDirection</tt>
     * @return the <tt>MediaDirection</tt> that should be used for the content
     * of type <tt>mediaType</tt> in the Jingle session for this
     * <tt>CallPeer</tt>.
     */
    private MediaDirection getJingleDirection(MediaType mediaType)
    {
        MediaDirection direction = MediaDirection.INACTIVE;
        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();

        // If we are streaming media, the direction should allow sending
        if ( (MediaType.AUDIO == mediaType &&
                mediaHandler.isLocalAudioTransmissionEnabled()) ||
             (MediaType.VIDEO == mediaType &&
                isLocalVideoStreaming()))
            direction = direction.or(MediaDirection.SENDONLY);

        // If we are receiving media from this CallPeer, the direction should
        // allow receiving
        SendersEnum senders = mediaHandler.getSenders(mediaType);
        if (senders == null || senders == SendersEnum.both ||
                    (isInitiator() && senders == SendersEnum.initiator) ||
                    (!isInitiator() && senders == SendersEnum.responder))
            direction = direction.or(MediaDirection.RECVONLY);

        // If RTP translation is enabled and we are receiving media from another
        // CallPeer in the same Call, the direction should allow sending
        if (mediaHandler.isRTPTranslationEnabled(mediaType))
        {
            for (CallPeerJabberImpl peer : getCall().getCallPeerList())
            {
                if (peer != this)
                {
                    senders = peer.getMediaHandler().getSenders(mediaType);
                    if (senders == null || senders == SendersEnum.both ||
                            (peer.isInitiator()
                                    && senders == SendersEnum.initiator) ||
                            (!peer.isInitiator()
                                    && senders == SendersEnum.responder))
                    {
                        direction = direction.or(MediaDirection.SENDONLY);
                        break;
                    }
                }
            }
        }

        return direction;
    }

    /**
     * Send a <tt>content</tt> message to reflect change in video setup (start
     * or stop). Message can be content-modify if video content exists,
     * content-add if we start video, but video is not enabled for the
     * <tt>CallPeer</tt>, or content-remove if we stop video and video is
     * enabled for the <tt>CallPeer</tt>.
     *
     */
    public void sendModifyVideoContent()
    {
        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();
        MediaDirection direction = getJingleDirection(MediaType.VIDEO);

        ContentPacketExtension remoteContent
            = mediaHandler.getLocalContent(MediaType.VIDEO.toString());

        if (remoteContent == null)
        {
            if (direction == MediaDirection.INACTIVE)
            {
                // no video content, none needed
                return;
            }
            else
            {
                if (logger.isInfoEnabled())
                    logger.info("Adding video content for " + this);
                sendAddVideoContent();
                return;
            }
        }
        else
        {
            if (direction == MediaDirection.INACTIVE)
            {
                // We could send a content-remove in this case, but instead
                // we just set senders=none
                //sendRemoveVideoContent();
                //return;
            }
        }

        SendersEnum senders = mediaHandler.getSenders(MediaType.VIDEO);
        if (senders == null)
            senders = SendersEnum.both;

        SendersEnum newSenders = SendersEnum.none;
        if (MediaDirection.SENDRECV == direction)
            newSenders = SendersEnum.both;
        else if (MediaDirection.RECVONLY == direction)
            newSenders = isInitiator()
                    ? SendersEnum.initiator : SendersEnum.responder;
        else if (MediaDirection.SENDONLY == direction)
            newSenders = isInitiator()
                    ? SendersEnum.responder : SendersEnum.initiator;

        /*
         * Send Content-Modify
         */
        ContentPacketExtension ext = new ContentPacketExtension();
        String remoteContentName = remoteContent.getName();

        ext.setSenders(newSenders);
        ext.setCreator(remoteContent.getCreator());
        ext.setName(remoteContentName);

        if (newSenders != senders)
        {
            if (logger.isInfoEnabled())
                logger.info("Sending content modify, senders: "
                        + senders + "->" + newSenders);
            ProtocolProviderServiceJabberImpl protocolProvider
                = getProtocolProvider();
            JingleIQ contentIQ
                = JinglePacketFactory.createContentModify(
                        protocolProvider.getOurJID(),
                        this.peerJID,
                        getSID(),
                        ext);

            protocolProvider.getConnection().sendPacket(contentIQ);
        }

        try
        {
            mediaHandler.reinitContent(remoteContentName, ext, false);
            mediaHandler.start();
        }
        catch(Exception e)
        {
            logger.warn("Exception occurred during media reinitialization", e);
        }
    }

    /**
     * Send a <tt>content</tt> message to reflect change in video setup (start
     * or stop).
     */
    public void sendModifyVideoResolutionContent()
    {
        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();
        ContentPacketExtension remoteContent
            = mediaHandler.getRemoteContent(MediaType.VIDEO.toString());
        ContentPacketExtension content;

        logger.info("send modify-content to change resolution");

        // send content-modify with RTP description

        // create content list with resolution
        try
        {
            content = mediaHandler.createContentForMedia(MediaType.VIDEO);
        }
        catch (Exception e)
        {
            logger.warn("Failed to gather content for video type", e);
            return;
        }

        // if we are only receiving video senders is null
        SendersEnum senders = remoteContent.getSenders();

        if (senders != null)
            content.setSenders(senders);

        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();
        JingleIQ contentIQ
            = JinglePacketFactory.createContentModify(
                    protocolProvider.getOurJID(),
                    this.peerJID,
                    getSID(),
                    content);

        protocolProvider.getConnection().sendPacket(contentIQ);

        try
        {
            mediaHandler.reinitContent(remoteContent.getName(), content, false);
            mediaHandler.start();
        }
        catch(Exception e)
        {
            logger.warn("Exception occurred when media reinitialization", e);
        }
    }

    /**
     * Send a <tt>content-remove</tt> to remove video setup.
     */
    private void sendRemoveVideoContent()
    {
        CallPeerMediaHandlerJabberImpl mediaHandler = getMediaHandler();

        ContentPacketExtension content = new ContentPacketExtension();
        ContentPacketExtension remoteContent
            = mediaHandler.getRemoteContent(MediaType.VIDEO.toString());
        String remoteContentName = remoteContent.getName();

        content.setName(remoteContentName);
        content.setCreator(remoteContent.getCreator());
        content.setSenders(remoteContent.getSenders());

        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();
        JingleIQ contentIQ
            = JinglePacketFactory.createContentRemove(
                    protocolProvider.getOurJID(),
                    this.peerJID,
                    getSID(),
                    Arrays.asList(content));

        protocolProvider.getConnection().sendPacket(contentIQ);
        mediaHandler.removeContent(remoteContentName);
        mediaHandler.setSenders(MediaType.VIDEO, SendersEnum.none);
    }

    /**
     * Sends local candidate addresses from the local peer to the remote peer
     * using the <tt>transport-info</tt> {@link JingleIQ}.
     *
     * @param contents the local candidate addresses to be sent from the local
     * peer to the remote peer using the <tt>transport-info</tt>
     * {@link JingleIQ}
     */
    protected void sendTransportInfo(Iterable<ContentPacketExtension> contents)
    {
        // if the call is canceled, do not start sending candidates in
        // transport-info
        if(cancelled)
            return;

        JingleIQ transportInfo = new JingleIQ();

        for (ContentPacketExtension content : contents)
            transportInfo.addContent(content);

        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();

        transportInfo.setAction(JingleAction.TRANSPORT_INFO);
        transportInfo.setFrom(protocolProvider.getOurJID());
        transportInfo.setSID(getSID());
        transportInfo.setTo(getAddress());
        transportInfo.setType(IQ.Type.SET);

        PacketCollector collector
            = protocolProvider.getConnection().createPacketCollector(
                    new PacketIDFilter(transportInfo.getPacketID()));

        protocolProvider.getConnection().sendPacket(transportInfo);
        collector.nextResult(SmackConfiguration.getPacketReplyTimeout());
        collector.cancel();
    }

    @Override
    public void setState(CallPeerState newState, String reason, int reasonCode)
    {
        try
        {
            /*
             * We need to dispose of the transport manager before the
             * 'call' field is set to null, because if Jitsi VideoBridge is in
             * use, it (the call) is needed in order to expire the
             * VideoBridge channels.
             */
            if (CallPeerState.DISCONNECTED.equals(newState)
                    || CallPeerState.FAILED.equals(newState))
                getMediaHandler().getTransportManager().close();
        }
        finally
        {
            super.setState(newState, reason, reasonCode);
        }
    }

    /**
     * Transfer (in the sense of call transfer) this <tt>CallPeer</tt> to a
     * specific callee address which may optionally be participating in an
     * active <tt>Call</tt>.
     *
     * @param to the address of the callee to transfer this <tt>CallPeer</tt> to
     * @param sid the Jingle session ID of the active <tt>Call</tt> between the
     * local peer and the callee in the case of attended transfer; <tt>null</tt>
     * in the case of unattended transfer
     * @throws OperationFailedException if something goes wrong
     */
    protected void transfer(String to, String sid)
        throws OperationFailedException
    {
        JingleIQ transferSessionInfo = new JingleIQ();
        ProtocolProviderServiceJabberImpl protocolProvider
            = getProtocolProvider();

        transferSessionInfo.setAction(JingleAction.SESSION_INFO);
        transferSessionInfo.setFrom(protocolProvider.getOurJID());
        transferSessionInfo.setSID(getSID());
        transferSessionInfo.setTo(getAddress());
        transferSessionInfo.setType(IQ.Type.SET);

        TransferPacketExtension transfer = new TransferPacketExtension();

        // Attended transfer.
        if (sid != null)
        {
            /*
             * Not really sure what the value of the "from" attribute of the
             * "transfer" element should be but the examples in "XEP-0251:
             * Jingle Session Transfer" has it in the case of attended transfer.
             */
            transfer.setFrom(protocolProvider.getOurJID());
            transfer.setSID(sid);

            // Puts on hold the 2 calls before making the attended transfer.
            OperationSetBasicTelephonyJabberImpl basicTelephony
                = (OperationSetBasicTelephonyJabberImpl)
                    protocolProvider.getOperationSet(
                        OperationSetBasicTelephony.class);
            CallPeerJabberImpl callPeer = basicTelephony.getActiveCallPeer(sid);
            if(callPeer != null)
            {
                if(!CallPeerState.isOnHold(callPeer.getState()))
                {
                    callPeer.putOnHold(true);
                }
            }

            if(!CallPeerState.isOnHold(this.getState()))
            {
                this.putOnHold(true);
            }
        }
        transfer.setTo(to);

        transferSessionInfo.addExtension(transfer);

        Connection connection = protocolProvider.getConnection();
        PacketCollector collector = connection.createPacketCollector(
                new PacketIDFilter(transferSessionInfo.getPacketID()));
        protocolProvider.getConnection().sendPacket(transferSessionInfo);

        Packet result
            = collector.nextResult(SmackConfiguration.getPacketReplyTimeout());

        if(result == null)
        {
            // Log the failed transfer call and notify the user.
            throw new OperationFailedException(
                    "No response to the \"transfer\" request.",
                    OperationFailedException.ILLEGAL_ARGUMENT);
        }
        else if (((IQ) result).getType() != IQ.Type.RESULT)
        {
            // Log the failed transfer call and notify the user.
            throw new OperationFailedException(
                    "Remote peer does not manage call \"transfer\"."
                    + "Response to the \"transfer\" request is: "
                    + ((IQ) result).getType(),
                    OperationFailedException.ILLEGAL_ARGUMENT);
        }
        else
        {
            String message = ((sid == null) ? "Unattended" : "Attended")
                + " transfer to: "
                + to;
            // Implements the SIP behavior: once the transfer is accepted, the
            // current call is closed.
            hangup(
                false,
                message,
                new ReasonPacketExtension(Reason.SUCCESS,
                    message,
                    new TransferredPacketExtension()));
        }
    }
}
