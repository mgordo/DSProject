/*
 * 2016 Royal Institute of Technology (KTH)
 *
 * LSelector is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.kth.news.core.leader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.news.core.LogTimeout;
import se.kth.news.core.news.NewsComp.SendTimeout;
import se.kth.news.core.news.util.NewsView;
import se.kth.news.core.news.util.NewsViewComparator;
import se.kth.news.play.News;
import se.sics.kompics.ClassMatchedHandler;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.network.Network;
import se.sics.kompics.network.Transport;
import se.sics.kompics.timer.CancelPeriodicTimeout;
import se.sics.kompics.timer.SchedulePeriodicTimeout;
import se.sics.kompics.timer.Timeout;
//import se.sics.kompics.simulator.network.identifier.Identifier;
import se.sics.kompics.timer.Timer;
import se.sics.ktoolbox.gradient.GradientPort;
import se.sics.ktoolbox.gradient.event.TGradientSample;
import se.sics.ktoolbox.gradient.util.GradientContainer;
import se.sics.ktoolbox.omngr.bootstrap.event.Sample;
import se.sics.ktoolbox.util.identifiable.Identifier;
import se.sics.ktoolbox.util.network.KAddress;
import se.sics.ktoolbox.util.network.KContentMsg;
import se.sics.ktoolbox.util.network.KHeader;

import se.sics.ktoolbox.util.network.basic.BasicContentMsg;
import se.sics.ktoolbox.util.network.basic.BasicHeader;

/**
 * @author Alex Ormenisan <aaor@kth.se>
 */
public class LeaderSelectComp extends ComponentDefinition {

    private static final Logger LOG = LoggerFactory.getLogger(LeaderSelectComp.class);
    private String logPrefix = " ";

    //*******************************CONNECTIONS********************************
    Positive<Timer> timerPort = requires(Timer.class);
    Positive<Network> networkPort = requires(Network.class);
    Positive<GradientPort> gradientPort = requires(GradientPort.class);
    Negative<LeaderSelectPort> leaderUpdate = provides(LeaderSelectPort.class);
    //*******************************EXTERNAL_STATE*****************************
    private KAddress selfAdr;
    //*******************************INTERNAL_STATE*****************************
    //private Comparator<NewsView> viewComparator;
    private Comparator viewComparator;
    private NewsViewComparator myComparator;
    private ArrayList<Identifier> neighborList;
    private TGradientSample lastSample;
    public LeaderSelectComp(Init init) {
        selfAdr = init.selfAdr;
        logPrefix = "<nid:" + selfAdr.getId() + ">";
        LOG.info("{}initiating...", logPrefix);
        
        viewComparator = viewComparator;
        myComparator = new NewsViewComparator();

        subscribe(handleStart, control);
        subscribe(handleGradientSample, gradientPort);
        subscribe(handleAmILeader, networkPort);
        subscribe(handleAmILeaderResponse, networkPort);
		subscribe(leaderPushTimeout, timerPort);
        subscribe(handleLeaderUpdatePush, networkPort);
    }

    Handler handleStart = new Handler<Start>() {
        @Override
        public void handle(Start event) {
            LOG.info("{}starting...", logPrefix);
        }
    };
	protected boolean isLeader;
	private static final int COMPARE_SAME_NEIGBOURS = 8; // TODO: test parameter
	private static final int LEADER_ROUNDS_LIMIT = 3; // TODO: test parameter
	private static final int TIMEOUT_REPUSH_LEADER = 10; // In seconds TODO: test parameter
	private static final int LEADER_TIMEOUT_DETECTION = 40;//In seconds TODO: Set to three reounds of leader updates or more
	private int leaderRounds = 0;
	private int waitingResponses = 0;


	private boolean iAmLeader = false;
	private boolean timeoutSet = false;
	
	
	// FOR LEADER
	private Integer leaderPushId = 0; // Each LeaderUpdatePush has an identifier
	private UUID pushTimerId =  null;
	private UUID timeoutUUID = null;
	// FOR NON-LEADERS
    private KAddress leaderAddress;
    private int lastLeaderPushId; // identifier to recognize if the LeaderUpdatePush was already seen or if to forward it to everyone who I know
	
	private int lastSeenPushIdTimeout;
    
    Handler handleGradientSample = new Handler<TGradientSample>() {
        @Override
        public void handle(TGradientSample sample) {
			lastSample = sample; // remember sample to have addresses of all neighbours

        	ArrayList<Identifier> new_neighborlist = new ArrayList<Identifier>();
        	//TODO check if we are leaders
			Iterator<Identifier> it = sample.getGradientNeighbours().iterator();
			

			//Determine if we are leader
			isLeader = true;
			NewsView selfView = (NewsView)sample.selfView;
			int unfamiliar_Nodes=0;
			while(it.hasNext()){
				GradientContainer<NewsView> neighbour = (GradientContainer<NewsView>)it.next();

				NewsView peerNews = neighbour.getContent();
				
				if (neighborList == null || !neighborList.contains(neighbour.getContent().nodeId)){
					unfamiliar_Nodes++;
					
				}
				
				if(myComparator.compare(selfView, peerNews) < 0){
//				if(viewComparator.compare((NewsView)sample.selfView, peerNews) < 0){
					isLeader=false;
					break;
				}
				new_neighborlist.add((Identifier) neighbour.getContent().nodeId);
				
			}
        	
			neighborList = new_neighborlist;
        	
        	
			if(unfamiliar_Nodes>COMPARE_SAME_NEIGBOURS || isLeader==false){
				leaderRounds = 0;
			}else{
				leaderRounds++;
				if (leaderRounds> LEADER_ROUNDS_LIMIT){
					leaderRounds = 0;

					if (iAmLeader) { // I'm already the leader, everyone should know. For those who joined later, there's a timeout when I'll push this information.
						return;
					}

					//Try to become a leader
					LOG.info("{}I want to be the leader!!!:{}", logPrefix);
					
					Iterator<Identifier> neighbourIt = sample.getGradientNeighbours().iterator();
					
					waitingResponses = sample.getGradientNeighbours().size();

					while (neighbourIt.hasNext()){
						
						GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();
						
						KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
			            KContentMsg msg = new BasicContentMsg(header, new AmILeader(selfView));
			            trigger(msg, networkPort);
						
					}
					
				}
			}

            //LOG.debug("{}neighbours:{}", logPrefix, sample.gradientNeighbours);
            //LOG.debug("{}fingers:{}", logPrefix, sample.gradientFingers);
            //LOG.debug("{}local view:{}", logPrefix, sample.selfView);
        }
    };

    
    ClassMatchedHandler<AmILeader, KContentMsg<?, ?, AmILeader>> handleAmILeader 
	= new ClassMatchedHandler<AmILeader, KContentMsg<?, ?, AmILeader>>() {

		@Override
		public void handle(AmILeader ami, KContentMsg<?, ?, AmILeader> container){
			//LOG.debug("{} I RECIEVED AM I LEADER", logPrefix);



			boolean isLeader = true;

        	// Check if any of my neighbours suits better for a leader
			if(lastSample == null) { // I don't know my neighbours, you can't be a leader yet!
				isLeader = false;
			}
			else {

				Iterator<Identifier> it = lastSample.getGradientNeighbours().iterator();

				while(it.hasNext()){
					GradientContainer<NewsView> neighbour = (GradientContainer<NewsView>)it.next();
					//NewsView selfView = (NewsView)sample.selfView;

					NewsView peerNews = neighbour.getContent();
					NewsView possibleLeader = ami.leaderView;

					if(myComparator.compare(possibleLeader, peerNews) < 0){
						//				if(viewComparator.compare((NewsView)sample.selfView, peerNews) < 0){
						isLeader = false;
						break;
					}

				}
			}
			
			KHeader header = new BasicHeader(selfAdr, container.getHeader().getSource(), Transport.UDP);
            KContentMsg msg = new BasicContentMsg(header, new AmILeaderResponse(isLeader));
            trigger(msg, networkPort);

		}
    
    
    };	
    
    void pushLeaderUpdate(){

		trigger(new LeaderUpdate(selfAdr), leaderUpdate); // transport this information to NewsComp

    	leaderPushId++;

    	Iterator<Identifier> neighbourIt = lastSample.getGradientNeighbours().iterator();
    	while (neighbourIt.hasNext()) { // Send it to all neighbours

    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();

    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(selfAdr, leaderPushId));
    		trigger(msg, networkPort);
    	}

    	Iterator<Identifier> fingerIt = lastSample.getGradientFingers().iterator();
    	while (fingerIt.hasNext()) { // Send it to all fingers

    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)fingerIt.next();

    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(selfAdr, leaderPushId));
    		trigger(msg, networkPort);
    	}
    }

    ClassMatchedHandler<AmILeaderResponse, KContentMsg<?, ?, AmILeaderResponse>> handleAmILeaderResponse 
    = new ClassMatchedHandler<AmILeaderResponse, KContentMsg<?, ?, AmILeaderResponse>>() {

    	@Override
    	public void handle(AmILeaderResponse ami, KContentMsg<?, ?, AmILeaderResponse> container){
    		if (ami.isLeader) {
    			waitingResponses--;

				LOG.debug("{} Someone agrees, {} remaining :)", logPrefix, waitingResponses);

				if (waitingResponses == 0) { // I'm the leader! Now let's inform everyone
					iAmLeader = true;

					LOG.debug("{}I AM THE LEADER!", logPrefix);

					pushLeaderUpdate();
					
					//set timeout to periodically send the LeaderUpdatePush
					SchedulePeriodicTimeout leaderRePushTimeout = new SchedulePeriodicTimeout(1000*TIMEOUT_REPUSH_LEADER, 1000*TIMEOUT_REPUSH_LEADER);
					LeaderTimeout timeout = new LeaderTimeout(leaderRePushTimeout);
					leaderRePushTimeout.setTimeoutEvent(timeout);
					trigger(leaderRePushTimeout, timerPort);
					pushTimerId = timeout.getTimeoutId();
				}
    		}
    		else
    			LOG.debug("{} Someone disagrees :(", logPrefix); //TODO: should each request have an ID and should we introduce timeouts?

    	}
    };
	

	

	ClassMatchedHandler<LeaderUpdatePush, KContentMsg<?, ?, LeaderUpdatePush>> handleLeaderUpdatePush
	= new ClassMatchedHandler<LeaderUpdatePush, KContentMsg<?, ?, LeaderUpdatePush>>() {

		@Override
		public void handle(LeaderUpdatePush update, KContentMsg<?, ?, LeaderUpdatePush> container) {

			if(!timeoutSet){
				SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(0, 1000*LEADER_TIMEOUT_DETECTION);
				UpdateTimeout timeout = new UpdateTimeout(spt);
				spt.setTimeoutEvent(timeout);
				trigger(spt, timerPort);
				timeoutUUID = timeout.getTimeoutId();
				timeoutSet=true;
			}
			if (update.leaderAdr.equals(leaderAddress) && update.id.equals(lastLeaderPushId)) // I have already processed this message, skip it
				return;
			else if (update.leaderAdr.equals(selfAdr)) // It's me, I don't want to forward my own message!
				return;


			// In case I was the leader before, I'm definitely not leader now
			iAmLeader = false;

			// The first time what I see this message, let's forward it to everyone I know.
			
			// First, remember this in case I received it again
			leaderAddress = update.leaderAdr;
			lastLeaderPushId = update.id;

			// Send the leader internally to NewsComp

			trigger(new LeaderUpdate(update.leaderAdr), leaderUpdate); // transport this information to NewsComp


			Iterator<Identifier> neighbourIt = lastSample.getGradientNeighbours().iterator();
			while (neighbourIt.hasNext()) { // Send it to all neighbours

				GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();

				KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(update.leaderAdr, update.id));
				trigger(msg, networkPort);
			}

			Iterator<Identifier> fingerIt = lastSample.getGradientFingers().iterator();
			while (fingerIt.hasNext()) { // Send it to all fingers

				GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)fingerIt.next();

				KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(update.leaderAdr, update.id));
				trigger(msg, networkPort);
			}

			LOG.info("{}received LeaderUpdatePush, leader is: {}", logPrefix, update.leaderAdr);

		}
	};



    public class LeaderTimeout extends Timeout {
    	public LeaderTimeout(SchedulePeriodicTimeout spt) {
    		super(spt);
    	}
    }

    Handler<LeaderTimeout> leaderPushTimeout = new Handler<LeaderTimeout>() {
    	public void handle(LeaderTimeout event) {

    		if (!iAmLeader) { // I am not leader anymore, don't send anything and cancel the timer
    			trigger(new CancelPeriodicTimeout(pushTimerId), timerPort);
    			return;
    		}

    		pushLeaderUpdate();
    	}
    };
	
    //This handler detects if the leader has not sent a LeaderPush in a certain time frame
    Handler<UpdateTimeout> sendTimeout = new Handler<UpdateTimeout>() {
		public void handle(UpdateTimeout event) {
			if(lastSeenPushIdTimeout==lastLeaderPushId && leaderAddress!=null){//No updates since last time we woke up, we forget info about leader
				iAmLeader=false;
				leaderAddress=null;
				leaderRounds =0;
				trigger(new LeaderUpdate(null), leaderUpdate); // transport this information to NewsComp
				trigger(new CancelPeriodicTimeout(timeoutUUID), timerPort);
				timeoutSet=false;
			}
			lastSeenPushIdTimeout=lastLeaderPushId;
		}
	};
    
    
    public static class Init extends se.sics.kompics.Init<LeaderSelectComp> {

        public final KAddress selfAdr;
        public final Comparator viewComparator;

        public Init(KAddress selfAdr, Comparator viewComparator) {
            this.selfAdr = selfAdr;
            this.viewComparator = viewComparator;
        }
    }
    
    public class UpdateTimeout extends Timeout {
		public UpdateTimeout(SchedulePeriodicTimeout spt) {
			super(spt);
		}
	}

}
