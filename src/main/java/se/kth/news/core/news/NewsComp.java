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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.news.core.leader.LeaderSelectPort;
import se.kth.news.core.leader.LeaderUpdate;
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
import se.sics.kompics.timer.Timer;
import se.sics.ktoolbox.croupier.CroupierPort;
import se.sics.ktoolbox.croupier.event.CroupierSample;
import se.sics.ktoolbox.gradient.GradientPort;
import se.sics.ktoolbox.gradient.event.TGradientSample;
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
    
    public NewsComp(Init init) {
        selfAdr = init.selfAdr;
        logPrefix = "<nid:" + selfAdr.getId() + ">";
        LOG.info("{}initiating...", logPrefix);

        gradientOId = init.gradientOId;

        subscribe(handleStart, control);
        subscribe(handleCroupierSample, croupierPort);
        subscribe(handleGradientSample, gradientPort);
        subscribe(handleLeader, leaderPort);
        subscribe(handlePing, networkPort);
        subscribe(handlePong, networkPort);
        subscribe(handleNews, networkPort);
    }

    Handler handleStart = new Handler<Start>() {
        @Override
        public void handle(Start event) {
            LOG.info("{}starting...", logPrefix);
            updateLocalNewsView();
            
        }
    };
	protected ArrayList<KAddress> peerlist = new ArrayList<KAddress>();
	protected HashSet<String> newshash = new HashSet<String>();

    private void updateLocalNewsView() {
        localNewsView = new NewsView(selfAdr.getId(), 0);
        LOG.debug("{}informing overlays of new view", logPrefix);
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
            
            if(hasSent==false){
				LOG.debug("{} about to send a news", logPrefix);
            	hasSent=true;
            	News newNew = new News(selfAdr.getIp().toString()+"_1");
            	newshash.add(newNew.getNewsId());
            	for(KAddress address : peerlist){
					LOG.debug("{} sending news to {}", logPrefix, address.toString());
            		KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
                    KContentMsg msg = new BasicContentMsg(header, newNew);
                    
            		trigger(msg, networkPort);
            	}
            	
            }
            
            
            /*KHeader header = new BasicHeader(selfAdr, partner, Transport.UDP);
            KContentMsg msg = new BasicContentMsg(header, new Ping());
            trigger(msg, networkPort);*/
        }
    };

    Handler handleGradientSample = new Handler<TGradientSample>() {
        @Override
        public void handle(TGradientSample sample) {
        }
    };

    Handler handleLeader = new Handler<LeaderUpdate>() {
        @Override
        public void handle(LeaderUpdate event) {
        }
    };
	

    ClassMatchedHandler handleNews
            = new ClassMatchedHandler<News, KContentMsg<?, ?, News>>() {

                @Override
                public void handle(News news, KContentMsg<?, ?, News> container) {
                    if(!newshash.contains(news.getNewsId())){
						LOG.info("{}received news from:{}, identifier:{}_{}", logPrefix, container.getHeader().getSource(), news.getNewsId(), selfAdr.toString());
                    	newshash.add(news.getNewsId());
                    	News newNew = new News(news);
                    	newNew.decreaseTTL();
                    	if(newNew.getTTL()>0){
                    		for(KAddress address : peerlist){
								LOG.info("{}forwarding news to:{}", logPrefix, address.toString());
                        		KHeader header = new BasicHeader(selfAdr, address, Transport.UDP);
                                KContentMsg msg = new BasicContentMsg(header, newNew);
                        		trigger(msg, networkPort);
                        		
                        	}
                    	}
                    	
                    	
                    }
                    //trigger(container.answer(new Pong()), networkPort);
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

    public static class Init extends se.sics.kompics.Init<NewsComp> {

        public final KAddress selfAdr;
        public final Identifier gradientOId;

        public Init(KAddress selfAdr, Identifier gradientOId) {
            this.selfAdr = selfAdr;
            this.gradientOId = gradientOId;
        }
    }
}
