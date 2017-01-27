/****************************************************************************
 *
 *   Copyright (c) 2017 Eike Mansfeld ecm@gmx.de. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 ****************************************************************************/

package com.comino.flight.widgets.charts.xy;

import java.util.List;

import com.comino.flight.model.AnalysisDataModel;
import com.comino.flight.model.KeyFigureMetaData;

public class XYStatistics {

	public float center_x;
	public float center_y;

	public float stddev_x;
	public float stddev_y;

	public float stddev_xy;
	public float radius;

	public float distance;

	private KeyFigureMetaData fy;
	private KeyFigureMetaData fx;


	public void setKeyFigures(KeyFigureMetaData fx, KeyFigureMetaData fy) {
		this.fx = fx; this.fy=fy;
	}

	public void getStatistics(int x0, int x1, List<AnalysisDataModel> list) {
		float vx=0; float vy=0; int i=0; float radius=0;

		x1 =  list.size() < x1 ? list.size()-1 : x1-1;

		if(list.size() < 10 || fx.hash==0 || fy.hash==0)
			return;

		for(i = x0; i< x1;i++) {
	        vx += list.get(i).getValue(fx);
	        vy += list.get(i).getValue(fy);
		}
		center_x = vx / (i - x0);
		center_y = vy / (i - x0);

		vx = 0; vy = 0;
		for(i = x0; i< x1 ;i++) {
	        vx += (list.get(i).getValue(fx) - center_x) * (list.get(i).getValue(fx) - center_x);
	        vy += (list.get(i).getValue(fy) - center_y) * (list.get(i).getValue(fy) - center_y);
	        if(Math.abs(list.get(i).getValue(fx)-center_x) > radius)
	        	radius = Math.abs(list.get(i).getValue(fx)-center_x);
	        if(Math.abs(list.get(i).getValue(fy)-center_y) > radius)
	        	radius = Math.abs(list.get(i).getValue(fy)-center_y);

		}

        this.radius = radius;
		stddev_x =(float)Math.sqrt( vx / (i - x0));
		stddev_y =(float)Math.sqrt( vy / (i - x0));

		distance =  (float)Math.sqrt(
				(list.get(0).getValue(fx) - list.get(x1).getValue(fx)) *
				(list.get(0).getValue(fx) - list.get(x1).getValue(fx)) +
				(list.get(0).getValue(fy) - list.get(x1).getValue(fy)) *
				(list.get(0).getValue(fy) - list.get(x1).getValue(fy)));

		stddev_xy = (float)Math.sqrt(stddev_x*stddev_x+stddev_y*stddev_y);
	}

	public String getHeader() {
		if(fx!=null && fy!=null)
		   return fx.desc1+" / "+fy.desc1+" ["+fx.uom+"]";
		return null;
	}

	public void clear() {
		center_x = 0;
		center_y = 0;
		stddev_x = 0;
		stddev_y = 0;
	}

}
