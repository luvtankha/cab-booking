import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

export const center = {lat:28.6139,lon:77.2090};
const tiles = import.meta.env.VITE_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const credit = import.meta.env.VITE_TILE_ATTRIBUTION || '© OpenStreetMap contributors';
const pair = p => [p.lat,p.lon];

export default function Map({from,to,driver,line,onPoint}) {
  const node=useRef(null), mapRef=useRef(null), vectors=useRef(null), click=useRef(onPoint);
  useEffect(()=>{click.current=onPoint;},[onPoint]);
  useEffect(()=>{
    const map=L.map(node.current,{scrollWheelZoom:false}).setView(pair(center),12);
    L.tileLayer(tiles,{maxZoom:19,attribution:credit}).addTo(map);
    vectors.current=L.layerGroup().addTo(map);mapRef.current=map;
    map.on('click',event=>click.current?.({lat:+event.latlng.lat.toFixed(5),lon:+event.latlng.lng.toFixed(5)}));
    const resize=new ResizeObserver(()=>map.invalidateSize(false));resize.observe(node.current);
    return()=>{resize.disconnect();map.remove();};
  },[]);
  useEffect(()=>{
    const layer=vectors.current;if(!layer)return;
    layer.clearLayers();
    const marker=(point,color,label)=>L.circleMarker(pair(point),{radius:8,color:'#ffffff',weight:3,fillColor:color,fillOpacity:1}).bindTooltip(label).addTo(layer);
    const route=line?.length?line:[from,to].filter(Boolean);
    if(route.length>1)L.polyline(route.map(pair),{color:'#258c75',weight:5,dashArray:line?.length?'':'9 7'}).addTo(layer);
    if(from)marker(from,'#258c75','Pickup');
    if(to)marker(to,'#db8751','Destination');
    if(driver)marker(driver,'#4d68d5','Driver');
  },[from,to,driver,line]);
  useEffect(()=>{
    const map=mapRef.current;if(!map)return;
    if(from&&to) map.fitBounds([pair(from),pair(to)],{padding:[48,48],maxZoom:15,animate:false});
  },[from?.lat,from?.lon,to?.lat,to?.lon]);
  return <div ref={node} className="trip-map" aria-label="Ride map"/>;
}
