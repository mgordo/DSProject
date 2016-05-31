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
package se.kth.news.core.news;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.news.core.LogTimeout;
import se.kth.news.core.leader.LeaderSelectPort;
import se.kth.news.core.leader.LeaderUpdate;
import se.kth.news.core.leader.LeaderUpdatePush;
import se.kth.news.core.news.util.NewsView;
import se.kth.news.play.News;
import se.kth.news.play.Ping;
import se.kth.news.play.Pong;
import se.sics.kompics.ClassMatchedHandler;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.network.Network;
import se.sics.kompics.network.Transport;
import se.sics.kompics.timer.SchedulePeriodicTimeout;
import se.sics.kompics.timer.Timeout;
import se.sics.kompics.timer.Timer;
import se.sics.ktoolbox.croupier.CroupierPort;
import se.sics.ktoolbox.croupier.event.CroupierSample;
import se.sics.ktoolbox.gradient.GradientPort;
import se.sics.ktoolbox.gradient.event.TGradientSample;
import se.sics.ktoolbox.gradient.util.GradientContainer;
import se.sics.ktoolbox.util.identifiable.Identifier;
import se.sics.ktoolbox.util.network.KAddress;
import se.sics.ktoolbox.util.network.KContentMsg;
import se.sics.ktoolbox.util.network.KHeader;
import se.sics.ktoolbox.util.network.basic.BasicContentMsg;
import se.sics.ktoolbox.util.network.basic.BasicHeader;
import se.sics.ktoolbox.util.overlays.view.OverlayViewUpdate;
import se.sics.ktoolbox.util.overlays.view.OverlayViewUpdatePort;

/**
 * @author Alex Ormenisan <aaor@kth.se>
 */
public class NewsComp extends ComponentDefinition {

	private static final Logger LOG = LoggerFactory.getLogger(NewsComp.class);
	private String logPrefix = " ";

	//*******************************CONNECTIONS********************************
	Positive<Timer> timerPort = requires(Timer.class);
	Positive<Network> networkPort = requires(Network.class);
	Positive<CroupierPort> croupierPort = requires(CroupierPort.class);
	Positive<GradientPort> gradientPort = requires(GradientPort.class);
	Positive<LeaderSelectPort> leaderPort = requires(LeaderSelectPort.class);
	Negative<OverlayViewUpdatePort> viewUpdatePort = provides(OverlayViewUpdatePort.class);
	//*******************************EXTERNAL_STATE*****************************
	private KAddress selfAdr;
	private Identifier gradientOId;
	//*******************************INTERNAL_STATE*****************************
	private NewsView localNewsView;
	private boolean hasSent = false;//TODO This is preliminary
	private int sentMessages = 0;
	
	private boolean iAmLeader = false;
	private boolean sentNews = false; //TODO temporary for testing

	// FOR NON-LEADERS
    private KAddress leaderAddress;
	
    private ArrayList<News> pendingNews;
    private TGradientSample lastSample;
    private static final double  SEND_MESSSAGE_PROBABILITY = 0.6;

	public NewsComp(Init init) {
		selfAdr = init.selfAdr;
		logPrefix = "<nid:" + selfAdr.getId() + ">";
		LOG.info("{}initiating...", logPrefix);

		gradientOId = init.gradientOId;
		localNewsView = new NewsView(selfAdr.getId(), 0);
		
		lastSample = null;
		leaderAddress = null;
		pendingNews = new ArrayList<News>();
		subscribe(handleStart, control);
		subscribe(handleCroupierSample, croupierPort);
		subscribe(handleGradientSample, gradientPort);
		subscribe(handleLeader, leaderPort);
		subscribe(handlePing, networkPort);
		subscribe(handlePong, networkPort);
		subscribe(handleNews, networkPort);
		subscribe(handleSendAllMessages, networkPort);
		subscribe(handleTimeout, timerPort);
		subscribe(sendTimeout, timerPort);
	}

	Handler handleStart = new Handler<Start>() {
		@Override
		public void handle(Start event) {
			LOG.info("{}starting...", logPrefix);

			updateLocalNewsView();
			
			SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(1000*890, 99999999);
			LogTimeout timeout = new LogTimeout(spt);
			spt.setTimeoutEvent(timeout);
			trigger(spt, timerPort);

			SchedulePeriodicTimeout spt2 = new SchedulePeriodicTimeout(1000*60, 1000*10+(int)(Math.random()*20000));
//			SchedulePeriodicTimeout spt2 = new SchedulePeriodicTimeout(1000*2, 1000);
			SendTimeout timeout2 = new SendTimeout(spt2);
			spt2.setTimeoutEvent(timeout2);
			trigger(spt2, timerPort);

		}
	};
	protected ArrayList<KAddress> peerlist = new ArrayList<KAddress>();
	protected HashSet<String> newshash = new HashSet<String>();
	protected ArrayList<News> newsList = new ArrayList<News>();
	private int count=0;
	private ArrayList<Identifier> neighborlist;
	private ArrayList<String> seenMessages = new ArrayList<String>();
	
	private void updateLocalNewsView() {
		//localNewsView = new NewsView(selfAdr.getId(), (int)(Math.random()*100));//THIS CHANGES NUMBER OF NODES IN GRADIENT
		localNewsView = new NewsView(selfAdr.getId(), 0);//THIS CHANGES NUMBER OF NODES IN GRADIENT
		//LOG.debug("{}informing overlays of new view, _{}", logPrefix, localNewsView.localNewsCount);
		trigger(new OverlayViewUpdate.Indication<>(gradientOId, false, localNewsView.copy()), viewUpdatePort);
	}
	//This function reacts to updated neighbour
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

			//TODO Temporary send news
			/**
			if (hasSent==false){
				hasSent = true;
				sendNews();
			}
			*/




			/*KHeader header = new BasicHeader(selfAdr, partner, Transport.UDP);
            KContentMsg msg = new BasicContentMsg(header, new Ping());
            trigger(msg, networkPort);*/
		}
	};

		
	
	private void sendNews() {
		//localNewsView = new NewsView(selfAdr.getId(), localNewsView.localNewsCount + 1);//THIS CHANGES NUMBER OF NODES IN GRADIENT
		
		if (sentNews) //TODO: temporary testing
			return;

		//LOG.debug("{} about to send a news", logPrefix);
		News newNew = new News(selfAdr.getIp().toString()+"_"+sentMessages);
		//newshash.add(newNew.getNewsId());
		seenMessages.add(newNew.getNewsId());
		pendingNews.add(newNew);
		if(leaderAddress!=null){
			
			for(News nw : pendingNews){
				sentMessages++;
				//LOG.debug("{} sending news to {}", logPrefix, address.toString());
				KHeader header = new BasicHeader(selfAdr, leaderAddress, Transport.TCP);//Changed to TCP to avoid timeouts between leader and sender of news
				KContentMsg msg = new BasicContentMsg(header, nw);

				trigger(msg, networkPort);
				break; //TODO temporary, testing
			}
			pendingNews.clear();
			sentNews = true;
			
			
		}
			
		
		

	}

	Handler handleGradientSample = new Handler<TGradientSample>() {
		

		@Override
		public void handle(TGradientSample sample) {
			lastSample = sample;
		/**
			
			//Iterator it = sample.getGradientNeighbours().iterator();
			
        	//KAddress closestHigherNeighbour = null;
        	//int ownNewsCount = ((NewsView)sample.selfView).localNewsCount;//Hope this is not always zero as it happened with Miguel
        	//int closestHigherCount = Integer.MAX_VALUE;
        	
        	//TODO check if we are leaders
			Iterator<Identifier> it = sample.getGradientNeighbours().iterator();
			//Determine if we are leaders
			while(it.hasNext()){
				
			}
        	
        	
        	it = sample.getGradientNeighbours().iterator();
        	
			//KAddress partner = castSample.publicSample.get(it.next()).getSource();
			ArrayList<Identifier> new_neighborlist = new ArrayList<Identifier>();
			//TO BE DONE ONLY BY THE LEADER
			if(isLeader){
				int unfamiliar_Nodes=0;
			
				while(it.hasNext()){
					GradientContainer<NewsView> neighbour = (GradientContainer<NewsView>)it.next();
					if(!neighborlist.contains(neighbour.getContent().nodeId)){
						unfamiliar_Nodes++;
						
					}
					else{
						continue;	
					}
					new_neighborlist.add(neighbour.getContent().nodeId);
				}
				neighborlist = new_neighborlist;
				if(unfamiliar_Nodes>5){
					
				}	
			}   
			
			//LOG.debug("{} utilityx value{}", logPrefix, localNewsView.localNewsCount);
			//LOG.debug("{} BINGO",logPrefix);
		*/
		}
	};

	//This handler is only for transporting information from LeaderSelectComp
	//DON'T INCLUDE THESE MESSAGES IN STATS
	Handler handleLeader = new Handler<LeaderUpdate>() {
		@Override
		public void handle(LeaderUpdate event) {
			
			if (leaderAddress == null && event.leaderAdr != null)

			leaderAddress = event.leaderAdr;

			if (event.leaderAdr != null && event.leaderAdr.equals(selfAdr)) // I am the leader, save this information
				iAmLeader = true;
			else
				iAmLeader = false;
		}
	};


	ClassMatchedHandler handleNews
	= new ClassMatchedHandler<News, KContentMsg<?, ?, News>>() {

		@Override
		public void handle(News news, KContentMsg<?, ?, News> container) {
			
			
			if(!newshash.contains(news.getNewsId())){
				//localNewsView = new NewsView(selfAdr.getId(), localNewsView.localNewsCount + 1);//THIS CHANGES NUMBER OF NODES IN GRADIENT
				//LOG.info("{}received news from:{}, identifier:{}_{}", logPrefix, container.getHeader().getSource(), news.getNewsId(), selfAdr.toString());
				newshash.add(news.getNewsId());
				newsList.add(news);
				News newNew = new News(news);
				seenMessages.add(news.getNewsId());
				newNew.decreaseTTL();
				if(newNew.getTTL() >= 19){
				//if(newNew.getTTL()>0){ //TODO:return back
					
					Iterator<Identifier> neighbourIt = lastSample.getGradientNeighbours().iterator();
			    	while (neighbourIt.hasNext()) { // Send it to all neighbours

			    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();

			    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
			    		//KContentMsg msg = new BasicContentMsg(header, newNew);
			    		KContentMsg msg = new BasicContentMsg(header, new News(newNew));
			    		trigger(msg, networkPort);
			    	}

			    	Iterator<Identifier> fingerIt = lastSample.getGradientFingers().iterator();
			    	while (fingerIt.hasNext()) { // Send it to all fingers

			    		GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)fingerIt.next();

			    		KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
			    		KContentMsg msg = new BasicContentMsg(header, new News(newNew));
			    		//KContentMsg msg = new BasicContentMsg(header, newNew);
			    		trigger(msg, networkPort);
			    	}
			    	/**
			    	for(KAddress address : peerlist){
						//LOG.info("{}forwarding news to:{}", logPrefix, address.toString());
						KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
						KContentMsg msg = new BasicContentMsg(header, newNew);
						trigger(msg, networkPort);

					}
					*/
					/*for( current : lastSample.getGradientNeighbours()){
						GradientContainer<NewsView> nw = (GradientContainer<NewsView>)current.selfView;
						
						KHeader header = new BasicHeader(selfAdr, nw.getSource(), Transport.UDP);
						KContentMsg msg = new BasicContentMsg(header, newNew);
						trigger(msg, networkPort);
						
					}*/
				}
			
			
			
			}
			
			
			
			
			
			/*
			if(!newshash.contains(news.getNewsId())){
				//localNewsView = new NewsView(selfAdr.getId(), localNewsView.localNewsCount + 1);//THIS CHANGES NUMBER OF NODES IN GRADIENT
				//LOG.info("{}received news from:{}, identifier:{}_{}", logPrefix, container.getHeader().getSource(), news.getNewsId(), selfAdr.toString());
				newshash.add(news.getNewsId());
				News newNew = new News(news);
				newNew.decreaseTTL();
				if(newNew.getTTL()>0){
					for(KAddress address : peerlist){
						//LOG.info("{}forwarding news to:{}", logPrefix, address.toString());
						KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
						KContentMsg msg = new BasicContentMsg(header, newNew);
						trigger(msg, networkPort);

					}
				}


			}*/
			//trigger(container.answer(new Pong()), networkPort);
		}
	};

	ClassMatchedHandler handleSendAllMessages // received only by a leader to send all messages it knows
	= new ClassMatchedHandler<SendAllMessages, KContentMsg<?, ?, SendAllMessages>>() {

		@Override
		public void handle(SendAllMessages content, KContentMsg<?, ?, SendAllMessages> container) {


			LOG.info("{}received SendAllMessages from:{}", logPrefix, container.getHeader().getSource());

			// TODO: now
			for (News news : newsList){

				News sendNews = new News(news);
				sendNews.restoreTTL();

				KHeader header = new BasicHeader(selfAdr, container.getHeader().getSource(), Transport.UDP);
				KContentMsg msg = new BasicContentMsg(header, sendNews);
				trigger(msg, networkPort);
			}

			trigger(container.answer(new Pong()), networkPort);
		}
	};

	ClassMatchedHandler handlePing
	= new ClassMatchedHandler<Ping, KContentMsg<?, ?, Ping>>() {

		@Override
		public void handle(Ping content, KContentMsg<?, ?, Ping> container) {
			LOG.info("{}received ping from:{}", logPrefix, container.getHeader().getSource());
			trigger(container.answer(new Pong()), networkPort);
		}
	};

	ClassMatchedHandler handlePong
	= new ClassMatchedHandler<Pong, KContentMsg<?, KHeader<?>, Pong>>() {

		@Override
		public void handle(Pong content, KContentMsg<?, KHeader<?>, Pong> container) {
			LOG.info("{}received pong from:{}", logPrefix, container.getHeader().getSource());
		}
	};

	Handler<LogTimeout> handleTimeout = new Handler<LogTimeout>() {
		public void handle(LogTimeout event) {
			LOG.info("{}Final log, received: {}, sent: {}", logPrefix, newshash.size(), sentMessages);
			count++;

			//TODO: skipping debug
/**
			PrintWriter file=null;

			try {
				file = new PrintWriter("C:\\Users\\Miguel\\git\\DSProject\\logs\\"+selfAdr.getId()+".txt", "UTF-8");
				System.out.println("Successfully printed to file");
				for(String line: seenMessages){
					file.println(line);
				}
				
				file.close();
			} catch (FileNotFoundException | UnsupportedEncodingException e) {
				System.out.println("Error creating file");
				e.printStackTrace();
			}
			*/
		}
	};

	Handler<SendTimeout> sendTimeout = new Handler<SendTimeout>() {
		public void handle(SendTimeout event) {
			/*if(Math.random()>=SEND_MESSSAGE_PROBABILITY){
				sendNews();
			}*/
			sendNews();
		}
	};

	public static class Init extends se.sics.kompics.Init<NewsComp> {

		public final KAddress selfAdr;
		public final Identifier gradientOId;

		public Init(KAddress selfAdr, Identifier gradientOId) {
			this.selfAdr = selfAdr;
			this.gradientOId = gradientOId;
		}
	}
	public class SendTimeout extends Timeout {
		public SendTimeout(SchedulePeriodicTimeout spt) {
			super(spt);
		}
	}
}


