'''
Created on May 10, 2016

@author: Miguel
'''
import matplotlib.pyplot as plt
import os
import re
from sets import Set
'''Change and add to the list as needed. Should contain path to archives'''
auxroute='C:/Users/Miguel/git/DSProject/'
archives = [auxroute+'plots/ttl2.log', auxroute+'plots/ttl5.log',auxroute+'plots/ttl20.log']
data=[]
count=0
seenLeader = False


def atoi(text):
    return int(text) if text.isdigit() else text

def natural_keys(text):
    
    return [ atoi(c) for c in re.split('(\d+)', text) ]



for ele in archives:
    total_sent = 0
    received = []
    f=open(os.path.normpath(ele),'r')
    for line in f:
        if "Final log" in line:
            parts = line.split(": ")
            total_sent+=int(parts[-1])
            
            received.append(int(parts[1].split(",")[0]))
            
        if "I AM THE LEADER!" in line and not seenLeader:
            parts = line.split("nid:")
            finalpart = parts[1].split(">")
            timepart = line.split("]")
            print "First Leader is "+finalpart[0]+" at time "+ timepart[0]
            seenLeader=True;
            
        if "First leader" in line:
            parts = line.split("nid:")
            finalpart = parts[1].split(">")
            timepart = line.split("]")
            print "First Leader received by "+finalpart[0]+" at time "+ timepart[0]
    
    print "Total sent file "+ele+": "+str(total_sent)
    
    percentages = [float(float(x)/total_sent)*100 for x in received]

    plt.bar(range(0,len(percentages)),percentages)
    plt.xlabel("Node")
    plt.ylabel("Percentage")
    plt.savefig(auxroute+'plots/nodes_'+str(count)+'.png')
    count+=1
    data.append(percentages)
    f.close()
    
plt.figure(figsize=(8,4))
plt.boxplot(data)
plt.ylabel("Percentages")

routefig=os.path.normpath(auxroute+'plots/boxplots.png')
plt.savefig(routefig,dpi=300,bbox_inches='tight')


print "Finished first part"




listas=[]
allmessages=Set([])

for fi in sorted(os.listdir(os.path.normpath(auxroute+'\logs')),key=natural_keys):
    if fi.endswith(".txt"):
        f=open(os.path.normpath(auxroute+'logs/'+fi))
        lista=[]
        for line in f:
            if(line.startswith('/')):
                
                lista.append(line[1:-1])
                
                if(line not in allmessages):
                    allmessages.add(line[1:-1])
                
            
            
            
        listas.append(lista)
        
        f.close()

for message in allmessages:
    
    recipients=[]
    count=0
    for lista in listas:
        count+=1
        if message in lista:
            recipients.append(count)
    
    print "This is the message: "+message
    print recipients
    fig = plt.figure(figsize=(8,4))    
    plt.bar([x for x in range(1,101,1)],[1 if x in recipients else 0 for x in range(1,101,1) ])
    plt.title(message)
    plt.xlabel("Nodes")
    plt.ylabel("Received")
    plt.xlim(0,100)
    routefig=os.path.normpath(auxroute+'plots/'+message+'.png')
    plt.savefig(routefig,dpi=300,bbox_inches='tight')
    plt.close(fig)




print "Fininshed succesfully!"