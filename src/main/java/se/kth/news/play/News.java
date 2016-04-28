package se.kth.news.play;

public class News {
	
	private String newsId;
	private int ttl=NEWS_TTL;
	private static final int NEWS_TTL=64;
	public News(String id){
		newsId = id;
		
	}
	public News (News oldNew){
		newsId = oldNew.getNewsId();
		ttl = oldNew.getTTL();
	}
	
	public String getNewsId(){
		return newsId;
	}
	
	public void decreaseTTL(){
		ttl--;
	}
	
	public int getTTL(){
		return ttl;
	}

}
