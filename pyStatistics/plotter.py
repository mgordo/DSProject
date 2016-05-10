'''
Created on May 10, 2016

@author: Miguel
'''
import matplotlib.pyplot as plt
import os

'''Change and add to the list as needed. Should contain path to archives'''
auxroute='C:/maven/'
archives = [auxroute+'/logs/xxx.log', auxroute+'/logs/yyy.log']
data=[]
for ele in archives:
    total_sent = 0
    received = []
    f=open(ele,'r')
    for line in f:
        if "Final log" in line:
            parts = line.split(": ")
            total_sent+=int(parts[-1])
            received.append(int(parts[1].split(",")[0]))

    
    print "Total sent file "+ele+": "+total_sent
    percentages = [x/total_sent for x in received]
    data.append(percentages)
    f.close()
    
plt.boxplot(data)
plt.ylabel("Percentages")

routefig=os.path.normpath(auxroute+'plots/boxplots.png')
plt.savefig()