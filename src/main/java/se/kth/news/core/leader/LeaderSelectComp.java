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
import se.kth.news.core.news.SendAllMessages;
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
import se.sics.ktoolbox.croupier.CroupierPort;
import se.sics.ktoolbox.croupier.event.CroupierSample;
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
	Positive<CroupierPort> croupierPort = requires(CroupierPort.class);
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
    private boolean isFirstTimeLeader=false;
    private boolean leaderFirstUpdate=true;

	private ArrayList<KAddress> peerlist = new ArrayList<KAddress>(); //Croupier
	private ArrayList<KAddress> lowerNeighbours = new ArrayList<KAddress>(); //reverse Gradient
    
	

    public LeaderSelectComp(Init init) {
        selfAdr = init.selfAdr;
        logPrefix = "<nid:" + selfAdr.getId() + ">";
        LOG.info("{}initiating...", logPrefix);
        
        viewComparator = viewComparator;
        myComparator = new NewsViewComparator();

		lowerNeighbours = new ArrayList<KAddress>();

        subscribe(handleStart, control);
		subscribe(handleCroupierSample, croupierPort);
        subscribe(handleGradientSample, gradientPort);
        subscribe(handleAmILeader, networkPort);
        subscribe(handleAmILeaderResponse, networkPort);
        subscribe(handleLeaderUpdatePush, networkPort);
        subscribe(handleSaveMyAddress, networkPort);
		subscribe(leaderPushTimeout, timerPort);
		subscribe(neighbourTimeout, timerPort);
		subscribe(sendTimeout, timerPort);

    }

    Handler handleStart = new Handler<Start>() {
        @Override
        public void handle(Start event) {
            LOG.info("{}starting...", logPrefix);
        }
    };
	protected boolean isLeader;
	private static final int COMPARE_SAME_NEIGBOURS = 10; 
	private static final int LEADER_ROUNDS_LIMIT = 5;
	private static final int TIMEOUT_REPUSH_LEADER = 30; // In seconds 
	private static final int FIRST_TIMEOUT_REPUSH_LEADER = 10; // In seconds 

	// WARNING: set also in NewsComp
	private static final int NEIGHBOURS_LIST_SIZE = 10; // How many neighbours to remember (received SaveMyAddress), for reverse gradient
	private static final int NEIGHBOURS_ROUNDS_LIMIT = 4; // For reverse gradient
	private static final int TIMEOUT_RESEND_ADDRESS = 30; // In seconds, how often a node sends its address to its neighbours (for reverse Gradient) 
	private static final int LEADER_TIMEOUT_DETECTION = 90;//In seconds, set to three rounds of leader updates or more
	private int leaderRounds = 0;
	private int neighboursRounds = 0;
	private boolean initialStageNeighbours = true; // neighboursRounds used only for the first time (initiating). Then a timer is set.
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
			isLeader = true; // Do I think I can be the leader?
			NewsView selfView = (NewsView)sample.selfView;
			int unfamiliar_Nodes=0;
			while(it.hasNext()){
				GradientContainer<NewsView> neighbour = (GradientContainer<NewsView>)it.next();

				NewsView peerNews = neighbour.getContent();
				
				if (neighborList == null || !neighborList.contains(neighbour.getContent().nodeId)){
					unfamiliar_Nodes++;
					
				}
				
				if(myComparator.compare(selfView, peerNews) < 0){ // A neighbour is better than me
//				if(viewComparator.compare((NewsView)sample.selfView, peerNews) < 0){
					isLeader = false;
				}
				new_neighborlist.add((Identifier) neighbour.getContent().nodeId);
				
			}
        	
			neighborList = new_neighborlist;


			if (unfamiliar_Nodes>COMPARE_SAME_NEIGBOURS){
				leaderRounds = 0;
				neighboursRounds = 0;
			}else{
				if (!isLeader) { // There are some better nodes than me
					leaderRounds = 0;
					neighboursRounds++;
					if (initialStageNeighbours && neighboursRounds > NEIGHBOURS_ROUNDS_LIMIT) { // send my address to all my neighbours and set a timer

						initialStageNeighbours = false; // since now, only timer is used, no neighboursRounds
						
						// Send my address to all my neighbours
						notifyNeighbours();
						
						// Set timeout to periodically notify neighbours
						SchedulePeriodicTimeout neighbourReSendTimeout = new SchedulePeriodicTimeout(1000*TIMEOUT_RESEND_ADDRESS, 1000*TIMEOUT_RESEND_ADDRESS);
						NeighbourTimeout timeout = new NeighbourTimeout(neighbourReSendTimeout);
						neighbourReSendTimeout.setTimeoutEvent(timeout);
						trigger(neighbourReSendTimeout, timerPort);
					}
				}
				else {
					neighboursRounds = 0;
					leaderRounds++;
					if (leaderRounds > LEADER_ROUNDS_LIMIT){
						leaderRounds = 0;

						if (iAmLeader) { // I'm already the leader, everyone should know. For those who joined later, there's a timeout when I'll push this information.
							return;
						}

						//Try to become a leader
						LOG.info("{}I want to be the leader!!! The leader is {} I know {} messages:", logPrefix, leaderAddress, selfView.localNewsCount);

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
    
    void pushLeaderUpdate(boolean newLeader){


		trigger(new LeaderUpdate(selfAdr), leaderUpdate); // transport this information to NewsComp

    	leaderPushId++;

		for (KAddress address : lowerNeighbours){
    		KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(selfAdr, leaderPushId, newLeader));
    		trigger(msg, networkPort);
			//LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, leaderPushId, address);
    	}

		LOG.debug("{} Pushing new leaderUpdate {}", logPrefix, leaderPushId);
		/**
    	Iterator<Identifier> neighbourIt = lastSample.getGradientNeighbours().iterator();
    	while (neighbourIt.hasNext()) { // Send it to all neighbours

    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();

    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(selfAdr, leaderPushId, newLeader));
    		trigger(msg, networkPort);
			LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, leaderPushId, current_container.getSource());
    	}
    	*/


/**
//		LOG.info("{}LeaderUpdatePush {} fingers:", logPrefix, leaderPushId);


		//LOG.info("{}LeaderUpdatePush x {} peerlist:", logPrefix, leaderPushId);
		for (KAddress address : peerlist){
			//LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, leaderPushId, address);

			KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(selfAdr, leaderPushId, newLeader));
			trigger(msg, networkPort);
		}
*/

/** FINGERS NOT WORKING, REPLACED WITH A CROUPIER SAMPLE
    	Iterator<Identifier> fingerIt = lastSample.getGradientFingers().iterator();
    	while (fingerIt.hasNext()) { // Send it to all fingers

    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)fingerIt.next();

    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(selfAdr, leaderPushId, newLeader));
    		trigger(msg, networkPort);
			LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, leaderPushId, current_container.getSource());
    	}
*/
    }

    void notifyNeighbours(){
		Iterator<Identifier> neighbourIt = lastSample.getGradientNeighbours().iterator();
    	while (neighbourIt.hasNext()) { // Send it to all neighbours

    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();

    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
    		KContentMsg msg = new BasicContentMsg(header, new SaveMyAddress());
    		trigger(msg, networkPort);
    	}

    }

	// WARNING: set also in NewsComp
    ClassMatchedHandler<SaveMyAddress, KContentMsg<?, ?, SaveMyAddress>> handleSaveMyAddress 
    = new ClassMatchedHandler<SaveMyAddress, KContentMsg<?, ?, SaveMyAddress>>() {

    	@Override
    	public void handle(SaveMyAddress ami, KContentMsg<?, ?, SaveMyAddress> container){
			//LOG.debug("{} SaveMyAddress received from {}", logPrefix, container.getHeader().getSource());
			KAddress address = container.getHeader().getSource();
			
			lowerNeighbours.remove(address); //If the address was in the list, remove it so that it can be added as the newest one
			
			if (lowerNeighbours.size() >= NEIGHBOURS_LIST_SIZE)
				lowerNeighbours.remove(0); // remove the oldest one to have space for this new one
			
			lowerNeighbours.add(address);
    	}
    };


    ClassMatchedHandler<AmILeaderResponse, KContentMsg<?, ?, AmILeaderResponse>> handleAmILeaderResponse 
    = new ClassMatchedHandler<AmILeaderResponse, KContentMsg<?, ?, AmILeaderResponse>>() {

    	@Override
    	public void handle(AmILeaderResponse ami, KContentMsg<?, ?, AmILeaderResponse> container){
    		if (ami.isLeader) {
    			waitingResponses--;

				LOG.debug("{} {} agrees, {} remaining :)", logPrefix, container.getHeader().getSource(), waitingResponses);

				if (waitingResponses == 0) { // I'm the leader! Now let's inform everyone
					iAmLeader = true;

					LOG.debug("{}I AM THE LEADER!", logPrefix);

					if(leaderAddress != null)
					{
						leaderFirstUpdate = false;
						pushLeaderUpdate(true);
					}
					
					leaderAddress = selfAdr;
					
					
					
					//set timeout to periodically send the LeaderUpdatePush
					SchedulePeriodicTimeout leaderRePushTimeout = new SchedulePeriodicTimeout(1000*FIRST_TIMEOUT_REPUSH_LEADER, 1000*TIMEOUT_REPUSH_LEADER);
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
			//LOG.debug("{} Handling push {}", logPrefix, update.id);

			if (update.leaderAdr.equals(leaderAddress) && update.id.equals(lastLeaderPushId)) // I have already processed this message, skip it
				return;
			else if (update.leaderAdr.equals(selfAdr)) // It's me, I don't want to forward my own message!
				return;

			if (leaderAddress != update.leaderAdr && update.leaderAdr != null && update.newLeader == false) {
				// I've gotten to know a new leader and this is not the leader's first update (that
				// means the leader has already been a leader for some time), so let's ask it for
				// all known messages

				LOG.debug("{} Ask all messages",logPrefix);
				KHeader header = new BasicHeader(selfAdr, update.leaderAdr, Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new SendAllMessages());
				trigger(msg, networkPort);
				
			}

			// First, remember this in case I received it again
			leaderAddress = update.leaderAdr;
			lastLeaderPushId = update.id;
			
			//Log first update received
			if(isFirstTimeLeader==false){
				LOG.debug("{} First leader update received",logPrefix);
				isFirstTimeLeader=true;
			}

			if(!timeoutSet){
				LOG.debug("{} timeout set {}, leader {}", logPrefix, lastLeaderPushId, update.leaderAdr);
				//LOG.debug("{} timeout set {}, leader {}", logPrefix, lastLeaderPushId, leaderAddress);
				SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(0, 1000*LEADER_TIMEOUT_DETECTION);
				UpdateTimeout timeout = new UpdateTimeout(spt);
				spt.setTimeoutEvent(timeout);
				trigger(spt, timerPort);
				timeoutUUID = timeout.getTimeoutId();
				timeoutSet=true;
			}
			
			// In case I was the leader before, I'm definitely not leader now
			iAmLeader = false;

			// The first time what I see this message, let's forward it to everyone I know.
			

			// Send the leader internally to NewsComp

			trigger(new LeaderUpdate(update.leaderAdr), leaderUpdate); // transport this information to NewsComp


			int numSent = 0;

			for (KAddress address : lowerNeighbours){
				//LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, update.id, address);
				numSent++;

				KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(update.leaderAdr, update.id, update.newLeader));
				trigger(msg, networkPort);
			}

/**
			Iterator<Identifier> neighbourIt = lastSample.getGradientNeighbours().iterator();
			while (neighbourIt.hasNext()) { // Send it to all neighbours
				numSent++;

				GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();

				KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(update.leaderAdr, update.id, update.newLeader));
				trigger(msg, networkPort);
			//	LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, update.id, current_container.getSource());
			}


			//LOG.info("{}LeaderUpdatePush {} peerlist:", logPrefix, update.id);
			for (KAddress address : peerlist){
				//LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, update.id, address);
				numSent++;

				KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(update.leaderAdr, update.id, update.newLeader));
				trigger(msg, networkPort);
			}
*/		
/** FINGERS NOT WORKING, REPLACED WITH A CROUPIER SAMPLE

			//LOG.info("{}LeaderUpdatePush {} fingers:", logPrefix, update.id);
			Iterator<Identifier> fingerIt = lastSample.getGradientFingers().iterator();
			while (fingerIt.hasNext()) { // Send it to all fingers
				numSent++;

				GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)fingerIt.next();

				KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, new LeaderUpdatePush(update.leaderAdr, update.id, update.newLeader));
				trigger(msg, networkPort);
			//	LOG.info("{}LeaderUpdatePush {} sent to {}", logPrefix, update.id, current_container.getSource());
			}

			//LOG.info("{}received LeaderUpdatePush {}, leader is: {}, sent to {} nodes", logPrefix, update.id, update.leaderAdr, numSent);
			 
*/

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
    			if(pushTimerId != null)
					trigger(new CancelPeriodicTimeout(pushTimerId), timerPort);
    			return;
    		}

    		pushLeaderUpdate(leaderFirstUpdate);
    		leaderFirstUpdate = false;
    	}
    };

	public class NeighbourTimeout extends Timeout {
    	public NeighbourTimeout(SchedulePeriodicTimeout spt) {
    		super(spt);
    	}
    }

	Handler<NeighbourTimeout> neighbourTimeout = new Handler<NeighbourTimeout>() {
    	public void handle(NeighbourTimeout event) {

    		notifyNeighbours();
    	}
    };
    //This handler detects if the leader has not sent a LeaderPush in a certain time frame
    Handler<UpdateTimeout> sendTimeout = new Handler<UpdateTimeout>() {
		public void handle(UpdateTimeout event) {
			if(lastSeenPushIdTimeout==lastLeaderPushId && leaderAddress!=null){//No updates since last time we woke up, we forget info about leader
				LOG.debug("{} timeout expired {}", logPrefix, lastSeenPushIdTimeout); // TODO: debug
				

				iAmLeader=false;
				leaderAddress=null;
				leaderRounds = 0;
				trigger(new LeaderUpdate(null), leaderUpdate); // transport this information to NewsComp
				trigger(new CancelPeriodicTimeout(timeoutUUID), timerPort);
				timeoutSet=false;
			}
			lastSeenPushIdTimeout=lastLeaderPushId;
		}
	};

	Handler handleCroupierSample = new Handler<CroupierSample<NewsView>>() {
		@Override
		public void handle(CroupierSample<NewsView> castSample) {

			if (castSample.publicSample.isEmpty()) {
				return;
			}
			Iterator<Identifier> it = castSample.publicSample.keySet().iterator();
			//KAddress partner = castSample.publicSample.get(it.next()).getSource();
			peerlist = new ArrayList<KAddress>();
			while(it.hasNext()){
				peerlist.add(castSample.publicSample.get(it.next()).getSource());
			}
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
