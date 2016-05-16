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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.news.core.news.util.NewsView;
import se.kth.news.play.News;
import se.sics.kompics.ClassMatchedHandler;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.network.Network;
import se.sics.kompics.network.Transport;
import se.sics.kompics.simulator.network.identifier.Identifier;
import se.sics.kompics.timer.Timer;
import se.sics.ktoolbox.gradient.GradientPort;
import se.sics.ktoolbox.gradient.event.TGradientSample;
import se.sics.ktoolbox.gradient.util.GradientContainer;
import se.sics.ktoolbox.omngr.bootstrap.event.Sample;
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
	protected static final int LEADER_ROUNDS_LIMIT = 3;
    private String logPrefix = " ";

    //*******************************CONNECTIONS********************************
    Positive<Timer> timerPort = requires(Timer.class);
    Positive<Network> networkPort = requires(Network.class);
    Positive<GradientPort> gradientPort = requires(GradientPort.class);
    Negative<LeaderSelectPort> leaderUpdate = provides(LeaderSelectPort.class);
    //*******************************EXTERNAL_STATE*****************************
    private KAddress selfAdr;
    //*******************************INTERNAL_STATE*****************************
    private Comparator viewComparator;
    private ArrayList<Identifier> neighborList;
    public LeaderSelectComp(Init init) {
        selfAdr = init.selfAdr;
        logPrefix = "<nid:" + selfAdr.getId() + ">";
        LOG.info("{}initiating...", logPrefix);
        
        viewComparator = viewComparator;

        subscribe(handleStart, control);
        subscribe(handleGradientSample, gradientPort);
        subscribe(handleAmILeader, networkPort);
    }

    Handler handleStart = new Handler<Start>() {
        @Override
        public void handle(Start event) {
            LOG.info("{}starting...", logPrefix);
        }
    };
	protected boolean isLeader;
	private static final int SHUFFLE_PARAM = 5;
	private int leaderRounds =0;
    
    Handler handleGradientSample = new Handler<TGradientSample>() {
        @Override
        public void handle(TGradientSample sample) {

        	ArrayList<Identifier> new_neighborlist = new ArrayList<Identifier>();
        	//TODO check if we are leaders
			Iterator<Identifier> it = sample.getGradientNeighbours().iterator();
			

			//Determine if we are leaders
			isLeader = true;
			int unfamiliar_Nodes=0;
			while(it.hasNext()){
				GradientContainer<NewsView> neighbour = (GradientContainer<NewsView>)it.next();

				NewsView peerNews = neighbour.getContent();
				
				if(!neighborList.contains(neighbour.getContent().nodeId)){
					unfamiliar_Nodes++;
					
				}
				
				if(viewComparator.compare(sample.selfView, peerNews) < 0){
					isLeader=false;
					break;
				}
				new_neighborlist.add((Identifier) neighbour.getContent().nodeId);
				
			}
        	
			neighborList = new_neighborlist;
        	
        	
			if(unfamiliar_Nodes>SHUFFLE_PARAM || isLeader==false){
				leaderRounds = 0;
			}else{
				leaderRounds++;
				if(leaderRounds> LEADER_ROUNDS_LIMIT){
					//Try to become a leader
					
					Iterator<Identifier> neighbourIt = neighborList.iterator();

					while(neighbourIt.hasNext()){
						
						GradientContainer<NewsView> current_container = (GradientContainer<NewsView>)neighbourIt.next();
						
						KHeader header = new BasicHeader(selfAdr, current_container.getSource(), Transport.UDP);
			            KContentMsg msg = new BasicContentMsg(header, new AmILeader());
			            trigger(msg, networkPort);
						
					}
						
						
						
						
					
					
					
				}
			}

            LOG.debug("{}neighbours:{}", logPrefix, sample.gradientNeighbours);
            LOG.debug("{}fingers:{}", logPrefix, sample.gradientFingers);
            LOG.debug("{}local view:{}", logPrefix, sample.selfView);
        }
    };

    
    ClassMatchedHandler<AmILeader, KContentMsg<?, ?, AmILeader>> handleAmILeader 
	= new ClassMatchedHandler<AmILeader, KContentMsg<?, ?, AmILeader>>() {

		@Override
		public void handle(AmILeader ami, KContentMsg<?, ?, AmILeader> container){
			LOG.debug("{} I RECIEVED AM I LEAER", logPrefix);
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
}
