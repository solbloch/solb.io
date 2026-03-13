#!/usr/bin/env python

from icalendar import Event, Calendar, vDatetime, vWeekday
from datetime import datetime
import sys
import re
import pytz
from textwrap import wrap
import tempfile, os


def event_parse(class_list, cal):
    if class_list[11] == 'ONLINE ASYNCHRONOUS' or class_list[2] == 'Dropped' or class_list[10] == 'TBA' or class_list[11] == 'TBA':
        return
    title = class_list[0]
    class_list = class_list[7:]
    for t in [class_list[i:i+7] for i in range(0,len(class_list),7)]:
        dtstart = datetime.strptime(t[3].split(' ')[1] + t[6].split(' ')[0],
                                    '%I:%M%p%m/%d/%Y').replace(tzinfo=pytz.timezone('America/New_York'))
        dtend = datetime.strptime(t[3].split(' ')[3] + t[6].split(' ')[0],
                                  '%I:%M%p%m/%d/%Y').replace(tzinfo=pytz.timezone('America/New_York'))
        until = datetime.strptime(t[3].split(' ')[3] + t[6].split(' ')[2],
                                  '%I:%M%p%m/%d/%Y').replace(tzinfo=pytz.timezone('America/New_York'))
        e = Event()
        e.add('SUMMARY', title)
        day_string = wrap(t[3].split(' ')[0].upper(), 2)
        e.add('RRULE', {'FREQ':'WEEKLY', 'BYDAY': day_string,
                        'UNTIL': until})
        e.add('DTSTART', dtstart)
        e.add('DTEND', dtend)
        e.add('LOCATION',t[4])
        e.add('DESCRIPTION',t[5]+' - '+t[2]+' '+t[1])
        cal.add_component(e)


def open_file(file_string):
    # with open (file_string, "r") as f:
    #     data = f.readlines()[1:-7]
    data = file_string.split('\n')[1:-7]
    classes = []
    class_temp = []
    for num, line in enumerate(data):
        if re.match('\d+\/\d+\/\d+', line):
            if num+1 < len(data):
                if re.match('[a-zA-Z]',data[num+1]):
                    class_temp.append(line)
                    classes.append(class_temp)
                    class_temp = []
                else:
                    class_temp.append(line)
            else:
                class_temp.append(line)
                classes.append(class_temp)
                class_temp = []
        else:
            class_temp.append(line)
    return classes


cal = Calendar()
classes = open_file(sys.argv[1])
print(classes)
for c in classes:
    event_parse(c, cal)
print(cal.to_ical())
